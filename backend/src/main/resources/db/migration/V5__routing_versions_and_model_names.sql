-- Catalog observations must not reset inference circuits or preferences.
ALTER TABLE provider_models ADD COLUMN routing_version BIGINT NOT NULL DEFAULT 0;
UPDATE provider_models SET routing_version = version;
-- Exact names and wildcard patterns share case-sensitive semantics.
ALTER TABLE virtual_models MODIFY COLUMN name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE provider_models MODIFY COLUMN model_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
ALTER TABLE model_rules MODIFY COLUMN pattern VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
