package com.llmgateway.infrastructure.credentials;

import com.llmgateway.admin.ProviderEntity;
import com.llmgateway.admin.ProviderRepository;
import com.llmgateway.config.CredentialProperties;
import com.llmgateway.config.GatewayProperties;
import com.llmgateway.inference.*;
import com.llmgateway.model.Provider;
import com.llmgateway.protocol.ProtocolJson;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@Service
public class CredentialService implements CredentialResolver {
    private final ProviderRepository providers;
    private final DatabaseClient database;
    private final CredentialProperties properties;
    private final CredentialEncryption encryption;
    public CredentialService(ProviderRepository providers, DatabaseClient database, CredentialProperties properties,
                             GatewayProperties gateway, Environment environment) {
        this.providers = providers; this.database = database; this.properties = properties;
        var keys = new HashMap<String, SecretKey>();
        if (properties.keyringFile() != null && !properties.keyringFile().isBlank()) {
            try {
                Path path = Path.of(properties.keyringFile());
                if (Files.size(path) > 65_536) throw new IllegalArgumentException();
                var json = ProtocolJson.read(Files.readString(path));
                if (!json.isObject()) throw new IllegalArgumentException();
                json.fields().forEachRemaining(entry -> keys.put(entry.getKey(), new SecretKeySpec(Base64.getDecoder().decode(ProtocolJson.text(entry.getValue())), "AES")));
            } catch (Exception ignored) { throw new IllegalArgumentException("Credential keyring is unavailable or invalid"); }
        }
        encryption = new CredentialEncryption(keys, properties.activeKeyId());
        if (properties.encryptedWrites() && properties.activeKeyId() == null) throw new IllegalArgumentException("Encrypted writes require an active key");
        if (environment.acceptsProfiles(Profiles.of("prod", "production")) && (!properties.encryptedWrites()
                || gateway.apiKey().isBlank() || gateway.apiKey().equals("dev-gateway-key"))) {
            throw new IllegalArgumentException("Production requires encryption and an explicit gateway key");
        }
    }
    public StoredCredential store(String providerId, String supplied, String plaintext, String ciphertext) {
        if (supplied == null || supplied.equals("***")) return new StoredCredential(plaintext, ciphertext);
        if (supplied.isBlank()) return new StoredCredential(null, null);
        return properties.encryptedWrites() ? new StoredCredential(null, encryption.encrypt(providerId, supplied)) : new StoredCredential(supplied, null);
    }
    @Override public Mono<String> resolve(String providerId) {
        return providers.findById(providerId).switchIfEmpty(Mono.error(new GatewayException(GatewayError.CREDENTIAL_CONFIGURATION)))
                .map(this::resolve).onErrorMap(e -> e instanceof GatewayException ? e : new GatewayException(GatewayError.CREDENTIAL_CONFIGURATION));
    }
    public String resolve(ProviderEntity provider) {
        if (provider.apiKeyCiphertext() != null) return encryption.decrypt(provider.id(), provider.apiKeyCiphertext());
        if (provider.apiKey() == null) return "";
        if (!properties.allowLegacyReads()) throw new GatewayException(GatewayError.CREDENTIAL_CONFIGURATION);
        return provider.apiKey();
    }
    public Provider access(ProviderEntity p) {
        return new Provider(p.id(), p.name(), p.baseUrl(), resolve(p), p.enabled(), p.protocol(),
                Duration.ofMillis(p.connectTimeoutMs()), Duration.ofMillis(p.readTimeoutMs()), Duration.ofMillis(p.requestTimeoutMs()),
                p.maxRetries(), p.modelDiscoveryEnabled(), p.modelDiscoveryUrl(), Duration.ofMillis(p.modelDiscoveryIntervalMs()), p.createdAt(), p.updatedAt());
    }
    public Mono<MigrationResult> migrate(int batchSize, boolean rotate) {
        if (!properties.encryptedWrites() || batchSize < 1 || batchSize > 1000) return Mono.error(new IllegalArgumentException("Migration requires encrypted writes and batch size 1 to 1000"));
        return providers.findAll().filter(p -> p.apiKey() != null || rotate && p.apiKeyCiphertext() != null && !encryption.usesActiveKey(p.apiKeyCiphertext()))
                .take(batchSize).concatMap(p -> Mono.fromCallable(() -> encryption.encrypt(p.id(), resolve(p)))
                        .flatMap(cipher -> database.sql("UPDATE providers SET api_key = NULL, api_key_ciphertext = :cipher, version = version + 1 WHERE id = :id AND version = :version")
                                .bind("cipher", cipher).bind("id", p.id()).bind("version", p.version()).fetch().rowsUpdated())
                        .map(count -> new MigrationRecord(p.id(), count == 1 ? null : "VERSION_CONFLICT"))
                        .onErrorResume(error -> Mono.just(new MigrationRecord(p.id(), error instanceof GatewayException
                                ? "CREDENTIAL_CONFIGURATION" : "PERSISTENCE_ERROR")))).collectList()
                .flatMap(results -> database.sql("SELECT COUNT(*) AS total FROM providers WHERE api_key IS NOT NULL")
                        .map((row, meta) -> row.get("total", Long.class)).one()
                        .zipWith(providers.findAll().filter(p -> p.apiKeyCiphertext() != null && !encryption.usesActiveKey(p.apiKeyCiphertext())).count())
                        .map(remaining -> new MigrationResult(results.stream().filter(r -> r.code() == null).count(),
                                results.stream().filter(r -> "VERSION_CONFLICT".equals(r.code())).count(),
                                results.stream().filter(r -> r.code() != null && !r.code().equals("VERSION_CONFLICT")).count(),
                                remaining.getT1(), remaining.getT2(), results.stream().filter(r -> r.code() != null)
                                        .map(r -> new MigrationFailure(r.providerId(), r.code())).toList())));
    }
    public record StoredCredential(String plaintext, String ciphertext) {
        @Override public String toString() { return "StoredCredential[redacted]"; }
    }
    private record MigrationRecord(String providerId, String code) {}
    public record MigrationFailure(String providerId, String code) {}
    public record MigrationResult(long migrated, long conflicts, long failed, long remainingLegacy,
                                  long remainingRotation, List<MigrationFailure> failures) {}
}
