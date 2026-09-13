CREATE TABLE provider_discovery_state (
    provider_id VARCHAR(36) PRIMARY KEY,
    allocated_generation BIGINT NOT NULL DEFAULT 0,
    applied_generation BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_discovery_provider FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE
);
ALTER TABLE provider_models
    ADD COLUMN missing_count INT NOT NULL DEFAULT 0,
    ADD COLUMN first_missing_at TIMESTAMP(6) NULL,
    ADD COLUMN observed_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN removal_source VARCHAR(32),
    ADD COLUMN pre_removal_status VARCHAR(32);
