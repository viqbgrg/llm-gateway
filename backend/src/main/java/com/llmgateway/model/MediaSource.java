package com.llmgateway.model;

import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/** Media references only; constructing a source never fetches or decodes the media format. */
public sealed interface MediaSource permits MediaSource.Url, MediaSource.InlineData {
    String mimeType();

    /** A remote HTTP(S) resource. Its MIME type may be unknown until fetched by the provider. */
    record Url(URI url, String mimeType) implements MediaSource {
        public Url {
            Objects.requireNonNull(url, "media URL is required");
            if (!("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                    || url.getHost() == null || url.getRawUserInfo() != null) {
                throw new IllegalArgumentException("media URL must be an absolute HTTP(S) URL without user info");
            }
            mimeType = mimeType == null ? null : normalizeMimeType(mimeType);
        }
    }

    /** Standard base64 without a data-URL prefix; MIME type is always explicit. */
    record InlineData(String data, String mimeType) implements MediaSource {
        public InlineData {
            Objects.requireNonNull(data, "inline media data is required");
            mimeType = normalizeMimeType(mimeType);
            if (data.isEmpty()) {
                throw new IllegalArgumentException("inline media data must not be empty");
            }
            try {
                Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("inline media data must be valid base64");
            }
        }
    }

    private static String normalizeMimeType(String mimeType) {
        Objects.requireNonNull(mimeType, "MIME type is required");
        // Concrete RFC 6838 type/subtype names; parameters belong in protocol-specific DTOs.
        if (!mimeType.matches("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}")) {
            throw new IllegalArgumentException("MIME type must be a concrete type/subtype without parameters");
        }
        return mimeType.toLowerCase(Locale.ROOT);
    }
}
