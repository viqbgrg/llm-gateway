ALTER TABLE model_rules
    ADD COLUMN virtual_model_id VARCHAR(36),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_rule_virtual_model FOREIGN KEY (virtual_model_id) REFERENCES virtual_models(id),
    ADD INDEX idx_rule_target (virtual_model_id),
    ADD INDEX idx_rule_priority (enabled, priority);
UPDATE model_rules SET enabled = FALSE WHERE virtual_model_id IS NULL;
-- A singleton lock serializes rule conflict checks across gateway instances.
CREATE TABLE routing_configuration_lock (id INT PRIMARY KEY);
INSERT INTO routing_configuration_lock (id) VALUES (1);
ALTER TABLE routing_policies
    ALTER COLUMN strategy SET DEFAULT 'PRIORITY',
    ADD COLUMN deadline_ms BIGINT NOT NULL DEFAULT 60000,
    ADD COLUMN max_total_attempts INT NOT NULL DEFAULT 3,
    ADD COLUMN allow_replay BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN backoff_ms BIGINT NOT NULL DEFAULT 100,
    ADD COLUMN max_backoff_ms BIGINT NOT NULL DEFAULT 2000,
    ADD COLUMN jitter DOUBLE NOT NULL DEFAULT 0.2,
    ADD COLUMN failure_threshold INT NOT NULL DEFAULT 8,
    ADD COLUMN cooldown_ms BIGINT NOT NULL DEFAULT 30000,
    ADD COLUMN half_open_permits INT NOT NULL DEFAULT 1,
    ADD COLUMN success_weight DOUBLE NOT NULL DEFAULT 0.5,
    ADD COLUMN latency_weight DOUBLE NOT NULL DEFAULT 0.3,
    ADD COLUMN health_weight DOUBLE NOT NULL DEFAULT 0.2,
    ADD COLUMN failure_weight DOUBLE NOT NULL DEFAULT 0.5,
    ADD COLUMN target_latency_ms BIGINT NOT NULL DEFAULT 1000,
    ADD COLUMN minimum_samples INT NOT NULL DEFAULT 5,
    ADD COLUMN penalty_half_life_ms BIGINT NOT NULL DEFAULT 60000,
    ADD COLUMN switch_threshold DOUBLE NOT NULL DEFAULT 0.1,
    ADD COLUMN minimum_hold_ms BIGINT NOT NULL DEFAULT 30000,
    ADD COLUMN preference_ttl_ms BIGINT NOT NULL DEFAULT 300000,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
