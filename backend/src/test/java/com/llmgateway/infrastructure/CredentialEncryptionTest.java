package com.llmgateway.infrastructure;

import com.llmgateway.infrastructure.credentials.CredentialEncryption;
import com.llmgateway.inference.GatewayException;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CredentialEncryptionTest {
    private final SecretKeySpec oldKey = new SecretKeySpec(new byte[32], "AES");
    private final SecretKeySpec newKey = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES");
    @Test void encryptsMaximumCredentialsWithUniqueNoncesAndProviderBoundAuthentication() {
        var cipher = new CredentialEncryption(Map.of("old", oldKey), "old");
        String secret = "密".repeat(1024);
        String first = cipher.encrypt("provider-1", secret), second = cipher.encrypt("provider-1", secret);
        assertThat(first).isNotEqualTo(second).doesNotContain(secret);
        assertThat(cipher.decrypt("provider-1", first)).isEqualTo(secret);
        assertThatThrownBy(() -> cipher.decrypt("provider-2", first)).isInstanceOf(GatewayException.class);
        String altered = first.substring(0, first.length() - 6) + "AAAAAA";
        assertThatThrownBy(() -> cipher.decrypt("provider-1", altered)).isInstanceOf(GatewayException.class);
    }
    @Test void rotatesTheActiveKeyWhileRetainingOldKeyReadSupport() {
        var old = new CredentialEncryption(Map.of("old", oldKey), "old");
        String envelope = old.encrypt("p", "synthetic-provider-key");
        var rotating = new CredentialEncryption(Map.of("old", oldKey, "new", newKey), "new");
        assertThat(rotating.decrypt("p", envelope)).isEqualTo("synthetic-provider-key");
        String rotated = rotating.encrypt("p", rotating.decrypt("p", envelope));
        assertThat(rotated).startsWith("v1.new.");
        var retired = new CredentialEncryption(Map.of("new", newKey), "new");
        assertThat(retired.decrypt("p", rotated)).isEqualTo("synthetic-provider-key");
        assertThatThrownBy(() -> retired.decrypt("p", envelope)).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new CredentialEncryption(Map.of("old", newKey), "old").decrypt("p", envelope)).isInstanceOf(GatewayException.class);
    }
}
