package com.llmgateway.model;

import java.util.Objects;

/** Protocol-neutral content. A variant determines its type and the payload it can carry. */
public sealed interface ContentBlock permits ContentBlock.Text, ContentBlock.Image, ContentBlock.Thinking,
        ToolCall, ToolResult, ContentBlock.Audio, ContentBlock.Video, ContentBlock.Document {
    ContentBlockType type();

    public static ContentBlock text(String value) {
        return new Text(value);
    }

    record Text(String text) implements ContentBlock {
        public Text {
            Objects.requireNonNull(text, "text is required");
        }

        @Override
        public ContentBlockType type() {
            return ContentBlockType.TEXT;
        }
    }

    record Image(MediaSource source) implements ContentBlock {
        public Image {
            validateMediaSource(source, "image");
        }

        @Override
        public ContentBlockType type() {
            return ContentBlockType.IMAGE;
        }
    }

    record Thinking(String text) implements ContentBlock {
        public Thinking {
            Objects.requireNonNull(text, "thinking text is required");
        }

        @Override
        public ContentBlockType type() {
            return ContentBlockType.THINKING;
        }
    }

    record Audio(MediaSource source) implements ContentBlock {
        public Audio {
            validateMediaSource(source, "audio");
        }

        @Override
        public ContentBlockType type() {
            return ContentBlockType.AUDIO;
        }
    }

    record Video(MediaSource source) implements ContentBlock {
        public Video {
            validateMediaSource(source, "video");
        }

        @Override
        public ContentBlockType type() {
            return ContentBlockType.VIDEO;
        }
    }

    record Document(MediaSource source) implements ContentBlock {
        public Document {
            Objects.requireNonNull(source, "document source is required");
        }

        @Override
        public ContentBlockType type() {
            return ContentBlockType.DOCUMENT;
        }
    }

    private static void validateMediaSource(MediaSource source, String mediaType) {
        Objects.requireNonNull(source, "media source is required");
        if (source.mimeType() != null && !source.mimeType().startsWith(mediaType + "/")) {
            throw new IllegalArgumentException("source MIME type must match the " + mediaType + " content block");
        }
    }
}
