package io.github.ykwyuta.llmmock.proxy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ykwyuta.llmmock.core.Provider;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingKeyTest {

    private static final List<String> REDACTED = List.of("key", "access_token");

    private String key(Provider provider, String method, String path, String query, String body) {
        return RecordingKey.of(provider, method, path, query,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), REDACTED);
    }

    @Test
    void identicalRequestsProduceTheSameKey() {
        assertThat(key(Provider.OPENAI, "POST", "/v1/chat/completions", null, "{\"a\":1}"))
                .isEqualTo(key(Provider.OPENAI, "POST", "/v1/chat/completions", null, "{\"a\":1}"));
    }

    @Test
    void everyPartOfTheRequestChangesTheKey() {
        String base = key(Provider.OPENAI, "POST", "/v1/chat/completions", "alt=sse", "{\"a\":1}");

        assertThat(key(Provider.ANTHROPIC, "POST", "/v1/chat/completions", "alt=sse", "{\"a\":1}"))
                .isNotEqualTo(base);
        assertThat(key(Provider.OPENAI, "GET", "/v1/chat/completions", "alt=sse", "{\"a\":1}"))
                .isNotEqualTo(base);
        assertThat(key(Provider.OPENAI, "POST", "/v1/completions", "alt=sse", "{\"a\":1}"))
                .isNotEqualTo(base);
        assertThat(key(Provider.OPENAI, "POST", "/v1/chat/completions", null, "{\"a\":1}"))
                .isNotEqualTo(base);
        assertThat(key(Provider.OPENAI, "POST", "/v1/chat/completions", "alt=sse", "{\"a\":2}"))
                .isNotEqualTo(base);
    }

    @Test
    void credentialsInTheQueryDoNotAffectTheKey() {
        // A recording made with one API key has to replay for a caller sending another.
        assertThat(key(Provider.GEMINI, "POST", "/v1beta/models/x:generateContent",
                "key=secret-one", "{}"))
                .isEqualTo(key(Provider.GEMINI, "POST", "/v1beta/models/x:generateContent",
                        "key=secret-two", "{}"));
    }

    @Test
    void queryParameterOrderDoesNotAffectTheKey() {
        assertThat(key(Provider.GEMINI, "POST", "/x", "alt=sse&pretty=true", "{}"))
                .isEqualTo(key(Provider.GEMINI, "POST", "/x", "pretty=true&alt=sse", "{}"));
    }

    @Test
    void aMeaningfulQueryParameterStillAffectsTheKey() {
        // alt=sse selects a completely different response format, so it must not be ignored.
        assertThat(key(Provider.GEMINI, "POST", "/x", "alt=sse", "{}"))
                .isNotEqualTo(key(Provider.GEMINI, "POST", "/x", null, "{}"));
    }

    @Test
    void canonicalQueryDropsRedactedParametersAndSortsTheRest() {
        assertThat(RecordingKey.canonicalQuery("b=2&key=secret&a=1", REDACTED)).isEqualTo("a=1&b=2");
        assertThat(RecordingKey.canonicalQuery(null, REDACTED)).isEmpty();
        assertThat(RecordingKey.canonicalQuery("", REDACTED)).isEmpty();
    }

    @Test
    void fileNamesAreDerivedFromTheProviderPathAndKey() {
        Recording recording = new Recording("abc123", Provider.OPENAI, java.time.Instant.now(),
                new Recording.RecordedRequest("POST", "/v1/chat/completions", null, null, null),
                new Recording.RecordedResponse(200, null, "{}", null));

        assertThat(RecordingStore.fileName(recording))
                .isEqualTo("openai__v1-chat-completions__abc123.json");
    }

    @Test
    void pathSlugsStayFilesystemSafeEvenForBedrockModelIds() {
        assertThat(RecordingStore.slug("/model/anthropic.claude-sonnet-4-5-20250929-v1:0/converse"))
                .doesNotContain("/")
                .contains("converse");
        assertThat(RecordingStore.slug("/")).isEqualTo("root");
        assertThat(RecordingStore.slug(null)).isEqualTo("root");
    }
}
