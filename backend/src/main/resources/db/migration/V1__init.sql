CREATE TABLE providers (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    api_key VARCHAR(1024),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    protocol VARCHAR(32) NOT NULL,
    connect_timeout_ms BIGINT NOT NULL DEFAULT 5000,
    read_timeout_ms BIGINT NOT NULL DEFAULT 30000,
    request_timeout_ms BIGINT NOT NULL DEFAULT 60000,
    max_retries INT NOT NULL DEFAULT 0,
    model_discovery_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    model_discovery_url VARCHAR(512),
    model_discovery_interval_ms BIGINT NOT NULL DEFAULT 1800000,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE provider_models (
    id VARCHAR(36) PRIMARY KEY,
    provider_id VARCHAR(36) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    capabilities JSON NOT NULL,
    raw_metadata JSON,
    first_seen_at TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_provider_models_provider FOREIGN KEY (provider_id) REFERENCES providers(id),
    CONSTRAINT uq_provider_model_name UNIQUE (provider_id, model_name)
);

CREATE TABLE routing_policies (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    strategy VARCHAR(32) NOT NULL DEFAULT 'ADAPTIVE',
    hedging_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    hedge_delay_ms BIGINT NOT NULL DEFAULT 800,
    max_hedge_count INT NOT NULL DEFAULT 1
);

CREATE TABLE virtual_models (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    routing_policy_id VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_virtual_model_policy FOREIGN KEY (routing_policy_id) REFERENCES routing_policies(id)
);

CREATE TABLE virtual_model_bindings (
    id VARCHAR(36) PRIMARY KEY,
    virtual_model_id VARCHAR(36) NOT NULL,
    provider_id VARCHAR(36) NOT NULL,
    provider_model_id VARCHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INT NOT NULL DEFAULT 0,
    translation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    source_protocol VARCHAR(32) NOT NULL,
    target_protocol VARCHAR(32) NOT NULL,
    capabilities_override JSON,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_binding_virtual_model FOREIGN KEY (virtual_model_id) REFERENCES virtual_models(id),
    CONSTRAINT fk_binding_provider FOREIGN KEY (provider_id) REFERENCES providers(id),
    CONSTRAINT fk_binding_provider_model FOREIGN KEY (provider_model_id) REFERENCES provider_models(id)
);

CREATE TABLE model_rules (
    id VARCHAR(36) PRIMARY KEY,
    pattern VARCHAR(255) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
