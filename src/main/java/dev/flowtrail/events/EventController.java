package dev.flowtrail.events;

import dev.flowtrail.error.ApiException;
import dev.flowtrail.persistence.RuntimeStore;
import dev.flowtrail.service.FlowTrailService;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/runs")
public class EventController {
  private final RuntimeStore store;
  private final FlowTrailService service;
  private final ThreadPoolExecutor streams =
      new ThreadPoolExecutor(
          8,
          8,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          r -> new Thread(r, "flowtrail-sse"),
          new ThreadPoolExecutor.AbortPolicy());

  public EventController(RuntimeStore store, FlowTrailService service) {
    this.store = store;
    this.service = service;
  }

  @GetMapping("/{id}/events/history")
  public List<RunEvent> history(
      @PathVariable String id, @RequestParam(defaultValue = "0") long after) {
    service.getRun(id);
    validateCursor(after);
    return store.events(id, after, 1000);
  }

  @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) String last) {
    service.getRun(id);
    long cursor = after;
    try {
      if (last != null && !last.isBlank()) cursor = Math.max(cursor, Long.parseLong(last));
    } catch (NumberFormatException ex) {
      throw ApiException.validation("Invalid Last-Event-ID");
    }
    validateCursor(cursor);
    SseEmitter emitter = new SseEmitter(30000L);
    AtomicBoolean closed = new AtomicBoolean();
    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(ex -> closed.set(true));
    final long initial = cursor;
    try {
      streams.execute(() -> deliver(id, initial, emitter, closed));
    } catch (RejectedExecutionException ex) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS, "SSE_CAPACITY", "SSE connection capacity reached");
    }
    return emitter;
  }

  private void deliver(String id, long cursor, SseEmitter emitter, AtomicBoolean closed) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(28);
    try {
      while (!closed.get() && System.nanoTime() < deadline) {
        boolean terminal = service.getRun(id).status().terminal();
        List<RunEvent> events = store.events(id, cursor, 100);
        for (RunEvent event : events) {
          emitter.send(
              SseEmitter.event().id(Long.toString(event.seq())).name("workflow").data(event));
          cursor = event.seq();
        }
        if (events.isEmpty() && terminal) {
          emitter.complete();
          return;
        }
        if (events.isEmpty()) Thread.sleep(100);
      }
      emitter.complete();
    } catch (Exception ex) {
      emitter.completeWithError(ex);
    }
  }

  private void validateCursor(long cursor) {
    if (cursor < 0) throw ApiException.validation("Event cursor must be nonnegative");
  }

  @PreDestroy
  public void close() {
    streams.shutdownNow();
  }
}
