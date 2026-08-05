CREATE TABLE gateway_backend_routing_group (
    backend_name VARCHAR(256) NOT NULL,
    routing_group VARCHAR(256) NOT NULL,
    PRIMARY KEY (backend_name, routing_group),
    FOREIGN KEY (backend_name) REFERENCES gateway_backend (name) ON DELETE CASCADE
);

-- Backfill the memberships from the single-valued column. Rows with no routing group
-- fall back to 'adhoc', the default value of the routing.defaultRoutingGroup configuration.
INSERT INTO gateway_backend_routing_group (backend_name, routing_group)
SELECT name, COALESCE(routing_group, 'adhoc') FROM gateway_backend;

ALTER TABLE gateway_backend DROP COLUMN routing_group;
