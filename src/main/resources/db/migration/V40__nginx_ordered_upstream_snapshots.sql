CREATE TABLE nginx_change_upstream (
 request_id BIGINT NOT NULL,
 purpose VARCHAR(8) NOT NULL,
 position INT NOT NULL,
 source_server_id BIGINT NULL,
 host VARCHAR(255) NOT NULL,
 port INT NOT NULL,
 PRIMARY KEY (request_id, purpose, position),
 CONSTRAINT fk_nginx_upstream_request FOREIGN KEY (request_id) REFERENCES nginx_change_request(id),
 CONSTRAINT ck_nginx_upstream_purpose CHECK (purpose IN ('PROXY','API')),
 CONSTRAINT ck_nginx_upstream_position CHECK (position >= 0 AND position < 32),
 CONSTRAINT ck_nginx_upstream_port CHECK (port >= 1 AND port <= 65535)
);
