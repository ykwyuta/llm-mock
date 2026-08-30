package com.example.llmmock.usage;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.llmmock.support.AppInstances;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cost analysis over proxied traffic: which model consumed which tokens, what that cost,
 * and how much the cache saved. The upstream is another instance of this application, so
 * the numbers are real end-to-end measurements rather than injected fixtures.
 */
class CostAnalysisTest {

    @TempDir
    Path recordingsDir;

    private final AppInstances instances = new AppInstances();
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void stopAll() {
        instances.close();
    }

    private ConfigurableApplicationContext startUpstream() {
        return instances.start(Map.of(
                "llm-mock.default-response-template", "[upstream] answered: {{prompt}}"));
    }

    private ConfigurableApplicationContext startProxy(String upstreamUrl, String mode) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("llm-mock.mode", mode);
        properties.put("llm-mock.proxy.recordings-dir", recordingsDir.toString());
        properties.put("llm-mock.proxy.targets.openai", upstreamUrl + "/openai");
        properties.put("llm-mock.proxy.targets.anthropic", upstreamUrl + "/anthropic");
        properties.put("llm-mock.cost.currency", "USD");
        // Prices per million tokens. Made up on purpose: no real price list is shipped,
        // because a stale number would silently produce a confident, wrong total.
        properties.put("llm-mock.cost.pricing[0].model-pattern", "^gpt-4o$");
        properties.put("llm-mock.cost.pricing[0].input", "2.50");
        properties.put("llm-mock.cost.pricing[0].output", "10.00");
        properties.put("llm-mock.cost.pricing[1].model-pattern", "^claude-sonnet");
        properties.put("llm-mock.cost.pricing[1].input", "3.00");
        properties.put("llm-mock.cost.pricing[1].output", "15.00");
        return instances.start(properties);
    }

    // --- HTTP helpers ------------------------------------------------------------------

    private String post(String url, String body) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private JsonNode getJson(String url) {
        try {
            return json.readTree(http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private String chat(String model, String prompt) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + prompt + "\"}]}";
    }

    private JsonNode rowFor(JsonNode summary, String model) {
        for (JsonNode row : summary.get("byModel")) {
            if (model.equals(row.get("model").asString())) {
                return row;
            }
        }
        throw new AssertionError("No summary row for " + model + " in " + summary);
    }

    // --- tests -------------------------------------------------------------------------

    @Test
    void proxiedCallsAreAccountedPerModel() {
        var upstream = startUpstream();
        var proxy = startProxy(AppInstances.urlOf(upstream), "PROXY");
        String base = AppInstances.urlOf(proxy);

        post(base + "/openai/v1/chat/completions", chat("gpt-4o", "Hello"));
        post(base + "/openai/v1/chat/completions", chat("gpt-4o", "Hello again"));
        post(base + "/anthropic/v1/messages", """
                {"model":"claude-sonnet-4-5","max_tokens":64,
                 "messages":[{"role":"user","content":"Hi"}]}""");

        JsonNode summary = getJson(base + "/__admin/usage/summary");

        assertThat(summary.get("currency").asString()).isEqualTo("USD");
        assertThat(summary.get("totals").get("requests").asInt()).isEqualTo(3);
        assertThat(summary.get("byModel")).hasSize(2);

        JsonNode openAi = rowFor(summary, "gpt-4o");
        assertThat(openAi.get("provider").asString()).isEqualTo("OPENAI");
        assertThat(openAi.get("requests").asInt()).isEqualTo(2);
        assertThat(openAi.get("inputTokens").asInt()).isPositive();
        assertThat(openAi.get("outputTokens").asInt()).isPositive();
        assertThat(openAi.get("priced").asBoolean()).isTrue();

        JsonNode anthropic = rowFor(summary, "claude-sonnet-4-5");
        assertThat(anthropic.get("provider").asString()).isEqualTo("ANTHROPIC");
        assertThat(anthropic.get("requests").asInt()).isEqualTo(1);
    }

    @Test
    void costIsComputedFromTheConfiguredPricePerMillionTokens() {
        var upstream = startUpstream();
        var proxy = startProxy(AppInstances.urlOf(upstream), "PROXY");
        String base = AppInstances.urlOf(proxy);

        post(base + "/openai/v1/chat/completions", chat("gpt-4o", "Hello"));

        JsonNode record = getJson(base + "/__admin/usage").get(0);
        int input = record.get("inputTokens").asInt();
        int output = record.get("outputTokens").asInt();

        BigDecimal expected = new BigDecimal("2.50").multiply(BigDecimal.valueOf(input))
                .add(new BigDecimal("10.00").multiply(BigDecimal.valueOf(output)))
                .divide(new BigDecimal("1000000"), 10, RoundingMode.HALF_UP);

        assertThat(new BigDecimal(record.get("estimatedCost").asString()))
                .isEqualByComparingTo(expected);
    }

    @Test
    void aCacheHitIsReportedAsSavingsRatherThanSpend() {
        var upstream = startUpstream();
        var proxy = startProxy(AppInstances.urlOf(upstream), "CACHED_PROXY");
        String base = AppInstances.urlOf(proxy);
        String body = chat("gpt-4o", "Hello");

        post(base + "/openai/v1/chat/completions", body);
        post(base + "/openai/v1/chat/completions", body);

        JsonNode totals = getJson(base + "/__admin/usage/summary").get("totals");

        assertThat(totals.get("upstreamRequests").asInt()).isEqualTo(1);
        assertThat(totals.get("cacheHits").asInt()).isEqualTo(1);
        // The cached answer is the recorded one, so it carries identical token counts:
        // what was saved is exactly what the one real call cost.
        BigDecimal spent = new BigDecimal(totals.get("upstreamCost").asString());
        BigDecimal saved = new BigDecimal(totals.get("cacheSavings").asString());
        assertThat(spent).isPositive().isEqualByComparingTo(saved);
        assertThat(new BigDecimal(totals.get("cost").asString()))
                .isEqualByComparingTo(spent.add(saved));
    }

    @Test
    void usageCanBeFilteredDownToWhatActuallyReachedAnUpstream() {
        var upstream = startUpstream();
        var proxy = startProxy(AppInstances.urlOf(upstream), "CACHED_PROXY");
        String base = AppInstances.urlOf(proxy);
        String body = chat("gpt-4o", "Hello");

        post(base + "/openai/v1/chat/completions", body);
        post(base + "/openai/v1/chat/completions", body);

        assertThat(getJson(base + "/__admin/usage?source=UPSTREAM")).hasSize(1);
        assertThat(getJson(base + "/__admin/usage?source=CACHE")).hasSize(1);
        assertThat(getJson(base + "/__admin/usage/summary?source=UPSTREAM")
                .get("totals").get("requests").asInt()).isEqualTo(1);
    }

    @Test
    void anUnpricedModelCountsItsTokensButReportsNoCost() {
        var upstream = startUpstream();
        var proxy = startProxy(AppInstances.urlOf(upstream), "PROXY");
        String base = AppInstances.urlOf(proxy);

        post(base + "/openai/v1/chat/completions", chat("some-unlisted-model", "Hello"));

        JsonNode summary = getJson(base + "/__admin/usage/summary");
        JsonNode row = rowFor(summary, "some-unlisted-model");

        assertThat(row.get("totalTokens").asInt()).isPositive();
        assertThat(row.get("priced").asBoolean()).isFalse();
        // No invented price, and the gap is named rather than left to be discovered.
        assertThat(row.get("cost")).isNull();
        assertThat(summary.get("unpricedModels").get(0).asString())
                .isEqualTo("some-unlisted-model");
    }

    @Test
    void streamedCallsAreAccountedToo() {
        var upstream = startUpstream();
        var proxy = startProxy(AppInstances.urlOf(upstream), "PROXY");
        String base = AppInstances.urlOf(proxy);

        post(base + "/openai/v1/chat/completions", """
                {"model":"gpt-4o","messages":[{"role":"user","content":"one two three"}],
                 "stream":true,"stream_options":{"include_usage":true}}""");
        post(base + "/anthropic/v1/messages", """
                {"model":"claude-sonnet-4-5","max_tokens":64,"stream":true,
                 "messages":[{"role":"user","content":"one two three"}]}""");

        JsonNode summary = getJson(base + "/__admin/usage/summary");

        assertThat(summary.get("totals").get("requests").asInt()).isEqualTo(2);
        assertThat(rowFor(summary, "gpt-4o").get("totalTokens").asInt()).isPositive();
        // Anthropic splits its counts across message_start and message_delta.
        JsonNode anthropic = rowFor(summary, "claude-sonnet-4-5");
        assertThat(anthropic.get("inputTokens").asInt()).isPositive();
        assertThat(anthropic.get("outputTokens").asInt()).isPositive();
    }

    @Test
    void mockedTrafficIsRecordedButTaggedAsSyntheticSoItCanBeExcluded() {
        var mock = instances.start(Map.of(
                "llm-mock.cost.pricing[0].model-pattern", "^gpt-4o$",
                "llm-mock.cost.pricing[0].input", "2.50",
                "llm-mock.cost.pricing[0].output", "10.00"));
        String base = AppInstances.urlOf(mock);

        post(base + "/openai/v1/chat/completions", chat("gpt-4o", "Hello"));

        assertThat(getJson(base + "/__admin/usage").get(0).get("source").asString())
                .isEqualTo("MOCK");
        assertThat(getJson(base + "/__admin/usage/summary?source=UPSTREAM")
                .get("totals").get("requests").asInt()).isZero();
    }

    @Test
    void resetClearsTheAccounting() {
        var mock = instances.start(Map.of());
        String base = AppInstances.urlOf(mock);
        post(base + "/openai/v1/chat/completions", chat("gpt-4o", "Hello"));
        assertThat(getJson(base + "/__admin/usage")).isNotEmpty();

        try {
            http.send(HttpRequest.newBuilder(URI.create(base + "/__admin/reset"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }

        assertThat(getJson(base + "/__admin/usage")).isEmpty();
    }
}
