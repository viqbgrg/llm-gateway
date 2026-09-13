-- Expand only. Application migration performs authenticated encryption with deployment key material.
ALTER TABLE providers ADD COLUMN api_key_ciphertext TEXT;
