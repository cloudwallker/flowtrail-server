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
