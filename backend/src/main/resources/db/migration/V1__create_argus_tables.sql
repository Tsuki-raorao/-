CREATE TABLE nodes (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_heartbeat TIMESTAMP NULL
);

CREATE TABLE instances (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    container_name VARCHAR(128) NOT NULL,
    version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_instances_node FOREIGN KEY (node_id) REFERENCES nodes(id)
);

CREATE TABLE tasks (
    id VARCHAR(64) PRIMARY KEY,
    instance_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    message VARCHAR(512),
    created_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    CONSTRAINT fk_tasks_instance FOREIGN KEY (instance_id) REFERENCES instances(id)
);

CREATE TABLE logs (
    id VARCHAR(64) PRIMARY KEY,
    instance_id VARCHAR(64) NOT NULL,
    log_timestamp TIMESTAMP NOT NULL,
    level VARCHAR(16) NOT NULL,
    message VARCHAR(4000) NOT NULL,
    CONSTRAINT fk_logs_instance FOREIGN KEY (instance_id) REFERENCES instances(id)
);

CREATE INDEX idx_instances_node_id ON instances(node_id);
CREATE INDEX idx_tasks_instance_created ON tasks(instance_id, created_at);
CREATE INDEX idx_logs_instance_timestamp ON logs(instance_id, log_timestamp);
