package com.llmgateway.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MediaSourceTest {
    private static final URI MEDIA_URL = URI.create("https://fixture.invalid/media?version=1");

    @ParameterizedTest
    @ValueSource(strings = {"http://fixture.invalid/media", "https://fixture.invalid/sub/image?version=1"})
    void preservesAbsoluteMediaUrlsAndAllowsUnknownMimeTypes(String value) {
        URI url = URI.create(value);
        var source = new MediaSource.Url(url, null);

        assertThat(source.url()).isEqualTo(url);
        assertThat(source.mimeType()).isNull();
        assertThat(new ContentBlock.Image(source).source()).isEqualTo(source);
        assertThat(new ContentBlock.Audio(source).source()).isEqualTo(source);
        assertThat(new ContentBlock.Video(source).source()).isEqualTo(source);
        assertThat(new ContentBlock.Document(source).source()).isEqualTo(source);
    }

    @Test
    void distinguishesAnImageUrlFromInlineImageData() {
        var remote = new ContentBlock.Image(new MediaSource.Url(MEDIA_URL, "IMAGE/PNG"));
        var inline = new ContentBlock.Image(new MediaSource.InlineData("AQ==", "IMAGE/PNG"));

        assertThat(remote.source()).isEqualTo(new MediaSource.Url(MEDIA_URL, "image/png"));
        assertThat(inline.source()).isEqualTo(new MediaSource.InlineData("AQ==", "image/png"));
        assertThat(remote).isNotEqualTo(inline);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AQ==", "AQ", "AQI=", "AQI", "AQID"})
    void preservesValidPaddedOrUnpaddedBase64(String data) {
        var source = new MediaSource.InlineData(data, "application/octet-stream");

        assertThat(source.data()).isEqualTo(data);
        assertThat(source.mimeType()).isEqualTo("application/octet-stream");
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"A", "%%%", "YQ===", "_w==", "YQ==\n", "data:image/png;base64,YQ=="})
    void rejectsEmptyOrMalformedInlineData(String data) {
        assertThatIllegalArgumentException().isThrownBy(() -> new MediaSource.InlineData(data, "image/png"));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"/relative", "//fixture.invalid/image", "file:///tmp/image", "http:opaque",
            "data:image/png;base64,AQ==", "https:///image", "https://user:password@fixture.invalid/image"})
    void rejectsNonHttpUrlsMissingHostsAndEmbeddedCredentials(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new MediaSource.Url(URI.create(value), null))
                .withMessage("media URL must be an absolute HTTP(S) URL without user info");
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "image", "/png", "image/", "image/*", "*/png",
            "image/png; charset=utf-8", "image/png\n"})
    void rejectsInvalidOrNonConcreteMimeTypes(String mimeType) {
        assertThatIllegalArgumentException().isThrownBy(() -> new MediaSource.Url(MEDIA_URL, mimeType));
        assertThatIllegalArgumentException().isThrownBy(() -> new MediaSource.InlineData("AQ==", mimeType));
    }

    @Test
    void requiresMediaPayloadsAndAnExplicitInlineMimeType() {
        assertThatNullPointerException().isThrownBy(() -> new MediaSource.Url(null, "image/png"));
        assertThatNullPointerException().isThrownBy(() -> new MediaSource.InlineData(null, "image/png"));
        assertThatNullPointerException().isThrownBy(() -> new MediaSource.InlineData("AQ==", null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDeclaredMimeTypesThatDoNotMatchTheContentModality(boolean inline) {
        MediaSource source = inline ? new MediaSource.InlineData("AQ==", "application/pdf")
                : new MediaSource.Url(MEDIA_URL, "application/pdf");

        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlock.Image(source));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlock.Audio(source));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlock.Video(source));
        assertThat(new ContentBlock.Document(source).source()).isEqualTo(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"})
    void representsDifferentDocumentFormatsWithoutInferringCapabilities(String mimeType) {
        var source = new MediaSource.InlineData("AQ==", mimeType);

        assertThat(new ContentBlock.Document(source).source().mimeType()).isEqualTo(mimeType);
    }
}
