CREATE TABLE nexus_agent_broker_binding (
    id INT PRIMARY KEY,
    server_id BIGINT NULL,
    agent_binding_id VARCHAR(64) NULL,
    FOREIGN KEY (server_id) REFERENCES server(id)
);
INSERT INTO nexus_agent_broker_binding (id,server_id,agent_binding_id) VALUES (1,NULL,NULL);
