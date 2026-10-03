CREATE TABLE IF NOT EXISTS workflows (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  nodes_json CLOB NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS runs (
  id VARCHAR(36) PRIMARY KEY,
  workflow_id VARCHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL,
  inputs_json CLOB NOT NULL,
  nodes_json CLOB NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  finished_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_runs_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id)
);

CREATE INDEX IF NOT EXISTS idx_workflows_created_at ON workflows(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_runs_workflow_started ON runs(workflow_id, started_at DESC);

CREATE TABLE IF NOT EXISTS workflow_version (
 workflow_id VARCHAR(36) NOT NULL, version INT NOT NULL, definition_json TEXT NOT NULL,
 PRIMARY KEY(workflow_id,version), FOREIGN KEY(workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS workflow_run (
 id VARCHAR(36) PRIMARY KEY, workflow_id VARCHAR(36) NOT NULL, version INT NOT NULL,
 status VARCHAR(24) NOT NULL, inputs_json TEXT NOT NULL, snapshot_json TEXT NOT NULL,
 models_json TEXT NOT NULL, request_key VARCHAR(128), request_hash VARCHAR(64) NOT NULL,
 owner VARCHAR(64), epoch BIGINT NOT NULL DEFAULT 0, lease_until TIMESTAMP(6),
 event_seq BIGINT NOT NULL DEFAULT 0, started_at TIMESTAMP(6) NOT NULL, finished_at TIMESTAMP(6),
 UNIQUE(workflow_id,request_key), FOREIGN KEY(workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS node_run (
 run_id VARCHAR(36) NOT NULL, node_id VARCHAR(40) NOT NULL, position_index INT NOT NULL,
 status VARCHAR(24) NOT NULL, attempt_id INT NOT NULL DEFAULT 0,
 output TEXT, error VARCHAR(500), duration_ms BIGINT NOT NULL DEFAULT 0, ready_at TIMESTAMP(6),
 PRIMARY KEY(run_id,node_id), FOREIGN KEY(run_id) REFERENCES workflow_run(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS node_attempt (
 run_id VARCHAR(36) NOT NULL, node_id VARCHAR(40) NOT NULL, attempt_id INT NOT NULL,
 status VARCHAR(24) NOT NULL, input_hash VARCHAR(64), output TEXT, error VARCHAR(500),
 started_at TIMESTAMP(6) NOT NULL, finished_at TIMESTAMP(6),
 PRIMARY KEY(run_id,node_id,attempt_id),
 FOREIGN KEY(run_id,node_id) REFERENCES node_run(run_id,node_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS run_event (
 run_id VARCHAR(36) NOT NULL, seq BIGINT NOT NULL, node_id VARCHAR(40), attempt_id INT,
 event_type VARCHAR(40) NOT NULL, payload_json TEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(run_id,seq), FOREIGN KEY(run_id) REFERENCES workflow_run(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS external_operation (
 run_id VARCHAR(36) NOT NULL, node_id VARCHAR(40) NOT NULL, operation_key VARCHAR(100) NOT NULL,
 state VARCHAR(24) NOT NULL, request_json TEXT NOT NULL, request_hash VARCHAR(64) NOT NULL,
 response TEXT, checks INT NOT NULL DEFAULT 0,
 PRIMARY KEY(run_id,node_id), UNIQUE(operation_key),
 FOREIGN KEY(run_id,node_id) REFERENCES node_run(run_id,node_id) ON DELETE CASCADE
);
