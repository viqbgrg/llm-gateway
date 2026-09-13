package com.llmgateway.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Test-only keys; deliberately deterministic and never used for deployment. */
public final class SyntheticKeyring {
    private static final String FILE = create();
    private SyntheticKeyring() {}
    public static String file() { return FILE; }

    private static String create() {
        try {
            byte[] rotated = new byte[32]; java.util.Arrays.fill(rotated, (byte) 1);
            Path path = Files.createTempFile("gateway-synthetic-keyring", ".json");
            path.toFile().deleteOnExit();
            Files.writeString(path, "{\"fixture\":\"" + Base64.getEncoder().encodeToString(new byte[32])
                    + "\",\"rotated\":\"" + Base64.getEncoder().encodeToString(rotated) + "\"}");
            return path.toString();
        } catch (Exception e) { throw new IllegalStateException("Cannot create synthetic test keyring", e); }
    }
}
