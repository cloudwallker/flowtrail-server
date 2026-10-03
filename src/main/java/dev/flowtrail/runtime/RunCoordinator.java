package dev.flowtrail.runtime;

import dev.flowtrail.api.*;
import dev.flowtrail.execution.*;
import dev.flowtrail.persistence.RuntimeStore;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Coordination never occupies a node worker and never waits for a child future. */
@Component
public class RunCoordinator {
  private static final Logger LOG = LoggerFactory.getLogger(RunCoordinator.class);
  private final RuntimeStore store;
  private final NodeExecutorRegistry executors;
  private final ThreadPoolExecutor workers;
  private final ScheduledExecutorService coordinator =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "flowtrail-coordinator"));
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Semaphore capacity;
  private final String owner = UUID.randomUUID().toString();
  private final int perRun, leaseSeconds;
  private final long[] delays;
  private final boolean enabled;
  private final AtomicBoolean stopped = new AtomicBoolean();

  public RunCoordinator(
      RuntimeStore store,
      NodeExecutorRegistry executors,
      @Value("${flowtrail.runtime.workers:8}") int count,
      @Value("${flowtrail.runtime.per-run:4}") int perRun,
      @Value("${flowtrail.runtime.queue-capacity:32}") int queue,
      @Value("${flowtrail.runtime.lease-seconds:15}") int leaseSeconds,
      @Value("${flowtrail.runtime.retry-delays-ms:1000,3000}") String delays,
      @Value("${flowtrail.runtime.enabled:true}") boolean enabled) {
    if (count < 1 || perRun < 1 || queue < 1 || leaseSeconds < 2)
      throw new IllegalArgumentException("Invalid runtime limits");
    this.store = store;
    this.executors = executors;
    this.perRun = perRun;
    this.leaseSeconds = leaseSeconds;
    this.enabled = enabled;
    this.delays =
        Arrays.stream(delays.split(",")).map(String::trim).mapToLong(Long::parseLong).toArray();
    if (this.delays.length != 2 || Arrays.stream(this.delays).anyMatch(d -> d < 0))
      throw new IllegalArgumentException("Exactly two nonnegative retry delays are required");
    this.workers =
        new ThreadPoolExecutor(
            count,
            count,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queue),
            r -> new Thread(r, "flowtrail-node"),
            new ThreadPoolExecutor.AbortPolicy());
    capacity = new Semaphore(count + queue);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (enabled) coordinator.scheduleWithFixedDelay(this::tickSafely, 0, 50, TimeUnit.MILLISECONDS);
  }

  private void tickSafely() {
    if (stopped.get()) return;
    try {
      for (Session session : List.copyOf(sessions.values())) {
        try {
          if (System.nanoTime() - session.lastRenew
              > TimeUnit.SECONDS.toNanos(Math.max(1, leaseSeconds / 3))) {
            store.renew(session.lease, leaseSeconds);
            session.lastRenew = System.nanoTime();
          }
          advance(session);
        } catch (StaleLeaseException ex) {
          sessions.remove(session.lease.runId(), session);
        }
      }
      if (sessions.size() < 32)
        for (String id : store.candidates()) {
          if (sessions.size() >= 32) break;
          if (sessions.containsKey(id)) continue;
          store
              .claim(id, owner, leaseSeconds)
              .ifPresent(lease -> sessions.put(id, new Session(lease)));
        }
    } catch (Exception ex) {
      LOG.warn("Runtime coordination deferred ({})", ex.getClass().getSimpleName());
    }
  }

  private void advance(Session session) {
    if (store.settle(session.lease)) {
      sessions.remove(session.lease.runId(), session);
      return;
    }
    RunSnapshot snapshot = store.snapshot(session.lease.runId());
    Map<String, NodeResult> results = new HashMap<>();
    snapshot.run().nodes().forEach(node -> results.put(node.id(), node));
    if (results.values().stream()
        .anyMatch(n -> n.status() == NodeStatus.FAILED || n.status() == NodeStatus.MANUAL_REVIEW))
      return;
    Map<String, NodeDefinition> definitions = new LinkedHashMap<>();
    snapshot.definitions().forEach(n -> definitions.put(n.id(), n));
    for (NodeDefinition node : snapshot.definitions()) {
      if (session.active.size() >= perRun) return;
      if (results.get(node.id()).status() != NodeStatus.PENDING
          || session.active.contains(node.id())) continue;
      if (node.dependsOn().stream()
          .anyMatch(id -> results.get(id).status() != NodeStatus.SUCCEEDED)) continue;
      if (!capacity.tryAcquire()) return;
      Map<String, String> ancestors = new LinkedHashMap<>();
      collectAncestors(node, definitions, results, ancestors);
      session.active.add(node.id());
      try {
        CompletableFuture.runAsync(
            () -> beginAndExecute(session, node, snapshot, ancestors), workers);
      } catch (RejectedExecutionException ex) {
        session.active.remove(node.id());
        capacity.release();
        // No attempt was started: leave the node pending for a later coordination pass.
      }
    }
  }

  private void beginAndExecute(
      Session session, NodeDefinition node, RunSnapshot snapshot, Map<String, String> ancestors) {
    boolean delegated = false;
    try {
      int attempt =
          store.begin(
              session.lease,
              node.id(),
              RuntimeStore.digest(
                  store.json(Map.of("inputs", snapshot.run().inputs(), "ancestors", ancestors))));
      if (attempt == 0) return;
      ExecutionContext context =
          new ExecutionContext(
              snapshot.run().id(),
              node.id(),
              attempt,
              snapshot.run().inputs(),
              ancestors,
              snapshot.models().get(node.modelRef()),
              text ->
                  store.append(
                      session.lease, node.id(), attempt, "LLM_DELTA", Map.of("text", text)),
              session.lease);
      delegated = true;
      execute(session, node, context);
    } catch (StaleLeaseException ignored) {
    } catch (Exception failure) {
      sessions.remove(session.lease.runId(), session);
      LOG.warn("Node start deferred to lease recovery ({})", failure.getClass().getSimpleName());
    } finally {
      if (!delegated) {
        session.active.remove(node.id());
        capacity.release();
      }
    }
  }

  private void execute(Session session, NodeDefinition node, ExecutionContext context) {
    long started = System.nanoTime();
    try {
      if (context.attemptId() > 3
          && !(node.type() == NodeType.HTTP && "POST".equals(node.method())))
        throw new NodeExecutionException("Interrupted attempt budget exhausted");
      String output = executors.get(node.type()).execute(node, context);
      store.complete(
          session.lease,
          node.id(),
          context.attemptId(),
          NodeStatus.SUCCEEDED,
          output,
          null,
          elapsed(started),
          0);
    } catch (StaleLeaseException ignored) {
      // The new owner is responsible for recovery. This callback cannot persist any result.
    } catch (Exception failure) {
      boolean retry =
          failure instanceof HttpFailure http
              && http.retryable()
              && node.type() == NodeType.HTTP
              && "GET".equals(node.method())
              && context.attemptId() <= delays.length;
      NodeStatus status =
          failure instanceof ManualReviewException
              ? NodeStatus.MANUAL_REVIEW
              : retry ? NodeStatus.PENDING : NodeStatus.FAILED;
      String error =
          failure instanceof NodeExecutionException
              ? failure.getMessage()
              : "Node execution failed";
      try {
        store.complete(
            session.lease,
            node.id(),
            context.attemptId(),
            status,
            null,
            error,
            elapsed(started),
            retry ? delays[context.attemptId() - 1] : 0);
      } catch (StaleLeaseException ignored) {
      } catch (Exception ex) {
        LOG.warn(
            "Node completion will recover after lease expiry ({})", ex.getClass().getSimpleName());
        sessions.remove(session.lease.runId(), session);
      }
    } finally {
      session.active.remove(node.id());
      capacity.release();
    }
  }

  private static long elapsed(long start) {
    return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private void collectAncestors(
      NodeDefinition node,
      Map<String, NodeDefinition> definitions,
      Map<String, NodeResult> results,
      Map<String, String> outputs) {
    for (String id : node.dependsOn())
      if (!outputs.containsKey(id)) {
        NodeResult result = results.get(id);
        if (result.status() != NodeStatus.SUCCEEDED)
          throw new IllegalStateException("Ancestor is not committed");
        outputs.put(id, result.output());
        collectAncestors(definitions.get(id), definitions, results, outputs);
      }
  }

  public int activeWorkers() {
    return workers.getActiveCount();
  }

  public int queuedWorkers() {
    return workers.getQueue().size();
  }

  @PreDestroy
  public void stop() {
    stopped.set(true);
    coordinator.shutdownNow();
    workers.shutdownNow();
    try {
      workers.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private static class Session {
    final Lease lease;
    final Set<String> active = ConcurrentHashMap.newKeySet();
    volatile long lastRenew = System.nanoTime();

    Session(Lease lease) {
      this.lease = lease;
    }
  }
}
