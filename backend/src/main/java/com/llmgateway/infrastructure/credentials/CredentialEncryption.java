package com.llmgateway.infrastructure.credentials;

import com.llmgateway.inference.GatewayError;
import com.llmgateway.inference.GatewayException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** v1.key-id.base64(nonce).base64(ciphertext || authentication tag). */
public final class CredentialEncryption {
    private final Map<String, SecretKey> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();
    public CredentialEncryption(Map<String, SecretKey> keys, String activeKeyId) {
        this.keys = Map.copyOf(keys); this.activeKeyId = activeKeyId;
        if (activeKeyId != null && !keys.containsKey(activeKeyId)) throw unavailable();
        keys.forEach((id, key) -> {
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || key.getEncoded().length != 32) throw unavailable();
        });
    }
    public String encrypt(String providerId, String plaintext) {
        try {
            if (activeKeyId == null) throw unavailable();
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(providerId, activeKeyId));
            byte[] result = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1." + activeKeyId + "." + Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(result);
        } catch (Exception ignored) { throw unavailable(); }
    }
    public String decrypt(String providerId, String envelope) {
        try {
            String[] parts = envelope.split("\\.", -1);
            if (parts.length != 4 || !parts[0].equals("v1") || !keys.containsKey(parts[1])) throw unavailable();
            byte[] nonce = Base64.getDecoder().decode(parts[2]);
            if (nonce.length != 12) throw unavailable();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(parts[1]), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(providerId, parts[1]));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[3])), StandardCharsets.UTF_8);
        } catch (Exception ignored) { throw unavailable(); }
    }
    public boolean usesActiveKey(String envelope) { return envelope != null && envelope.startsWith("v1." + activeKeyId + "."); }
    private static byte[] aad(String providerId, String keyId) { return ("llm-gateway:v1:" + keyId + ":" + providerId).getBytes(StandardCharsets.UTF_8); }
    private static GatewayException unavailable() { return new GatewayException(GatewayError.CREDENTIAL_CONFIGURATION); }
}
