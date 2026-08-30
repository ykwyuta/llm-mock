package com.example.llmmock.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.llmmock.core.MockMode;
import com.example.llmmock.core.Provider;

/** Everything about the mock's behaviour that a test suite may want to retune. */
@ConfigurationProperties(prefix = "llm-mock")
public class LlmMockProperties {

    private final Paths paths = new Paths();
    private final Cost cost = new Cost();
    private final Proxy proxy = new Proxy();
    private final Replay replay = new Replay();
    private final Stream stream = new Stream();
    private final Recording recording = new Recording();
    private final Models models = new Models();
    private final Embedding embedding = new Embedding();

    /**
     * Answer used when no stub rule matches. Supports {@code {{prompt}}} (last user turn),
     * {@code {{model}}}, {@code {{provider}}} and {@code {{messageCount}}}.
     */
    private String defaultResponseTemplate = "[llm-mock] echo: {{prompt}}";

    /** Require a credential header on provider endpoints and reject the request when absent. */
    private boolean requireAuth = false;

    /** Default answering mode for every provider. */
    private MockMode mode = MockMode.MOCK;

    /** Per-provider overrides of {@link #mode}, e.g. record only OpenAI while mocking the rest. */
    private final Map<Provider, MockMode> providerModes = new LinkedHashMap<>();

    public MockMode getMode() { return mode; }
    public void setMode(MockMode mode) { this.mode = mode == null ? MockMode.MOCK : mode; }
    public Map<Provider, MockMode> getProviderModes() { return providerModes; }

    /** The mode in force for one provider: its own override, else the global default. */
    public MockMode modeFor(Provider provider) {
        return providerModes.getOrDefault(provider, mode);
    }

    /** True when any provider is in a mode that needs the proxy/replay machinery. */
    public boolean anyNonMockMode() {
        if (mode != MockMode.MOCK) {
            return true;
        }
        return providerModes.values().stream().anyMatch(value -> value != MockMode.MOCK);
    }

    public Paths getPaths() { return paths; }
    public Cost getCost() { return cost; }
    public Proxy getProxy() { return proxy; }
    public Replay getReplay() { return replay; }
    public Stream getStream() { return stream; }
    public Recording getRecording() { return recording; }
    public Models getModels() { return models; }
    public Embedding getEmbedding() { return embedding; }
    public String getDefaultResponseTemplate() { return defaultResponseTemplate; }
    public void setDefaultResponseTemplate(String v) { this.defaultResponseTemplate = v; }
    public boolean isRequireAuth() { return requireAuth; }
    public void setRequireAuth(boolean requireAuth) { this.requireAuth = requireAuth; }

    /**
     * Per-provider URL prefixes. They exist because the real APIs collide: OpenAI and
     * Anthropic both serve {@code GET /v1/models} with different payloads. Point an SDK's
     * base URL at {@code http://localhost:8080<prefix>} and the rest of its paths line up.
     * Set a prefix to empty to mount that provider at the server root.
     */
    public static class Paths {
        private String openai = "/openai";
        private String anthropic = "/anthropic";
        private String gemini = "/gemini";
        private String bedrock = "/bedrock";

        public String getOpenai() { return openai; }
        public void setOpenai(String v) { this.openai = v; }
        public String getAnthropic() { return anthropic; }
        public void setAnthropic(String v) { this.anthropic = v; }
        public String getGemini() { return gemini; }
        public void setGemini(String v) { this.gemini = v; }
        public String getBedrock() { return bedrock; }
        public void setBedrock(String v) { this.bedrock = v; }
    }

    /** Settings for {@link MockMode#PROXY}. */
    public static class Proxy {

        /**
         * Upstream base URL per provider. The provider's own prefix is stripped from the
         * inbound path and the remainder is appended, so
         * {@code /openai/v1/chat/completions} becomes {@code <target>/v1/chat/completions}.
         */
        private final Map<Provider, String> targets = new LinkedHashMap<>();

        /**
         * Headers to set on the forwarded request, per provider. This is how a real
         * credential reaches the upstream while the application under test keeps sending a
         * dummy one to the mock.
         */
        private final Map<Provider, Map<String, String>> headers = new LinkedHashMap<>();

        /**
         * AWS SigV4 re-signing, per provider. A signature covers the {@code Host} header
         * and the path, so the caller's signature - made for this mock - is worthless
         * upstream and the request has to be signed again for the real endpoint.
         */
        private final Map<Provider, SigV4> sigv4 = new LinkedHashMap<>();

        /** Where recordings are written and read. */
        private String recordingsDir = "./recordings";

        /** Write a recording file for each proxied exchange. */
        private boolean record = true;

        /**
         * Request headers whose values are replaced with a placeholder before writing a
         * recording. Recordings are meant to be committed, so credentials must not survive.
         */
        private List<String> redactHeaders = List.of("authorization", "x-api-key",
                "x-goog-api-key", "api-key", "proxy-authorization", "cookie", "set-cookie",
                "x-amz-security-token");

        /** Query parameters redacted for the same reason, e.g. Gemini's {@code ?key=}. */
        private List<String> redactQueryParams = List.of("key", "access_token");

        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(120);

        private final Cache cache = new Cache();

        public Cache getCache() { return cache; }

        public Map<Provider, String> getTargets() { return targets; }
        public Map<Provider, Map<String, String>> getHeaders() { return headers; }
        public Map<Provider, SigV4> getSigv4() { return sigv4; }
        public String getRecordingsDir() { return recordingsDir; }
        public void setRecordingsDir(String v) { this.recordingsDir = v; }
        public boolean isRecord() { return record; }
        public void setRecord(boolean v) { this.record = v; }
        public List<String> getRedactHeaders() { return redactHeaders; }
        public void setRedactHeaders(List<String> v) { this.redactHeaders = v; }
        public List<String> getRedactQueryParams() { return redactQueryParams; }
        public void setRedactQueryParams(List<String> v) { this.redactQueryParams = v; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration v) { this.requestTimeout = v; }
    }

    /** Settings for {@link MockMode#CACHED_PROXY}. */
    public static class Cache {

        /**
         * How long a recording may answer before the upstream is consulted again. Zero or
         * unset means a recording never goes stale, which is what a test suite wants.
         */
        private Duration ttl;

        /**
         * Add {@code X-Llm-Mock-Source} to proxied, cached and replayed responses so a hit
         * can be told from a miss without reading the server log.
         */
        private boolean sourceHeader = true;

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration v) { this.ttl = v; }
        public boolean isSourceHeader() { return sourceHeader; }
        public void setSourceHeader(boolean v) { this.sourceHeader = v; }
    }

    /** Token accounting and the price list used to turn it into money. */
    public static class Cost {

        private boolean enabled = true;

        /** Purely a label on the output; no conversion is performed. */
        private String currency = "USD";

        /**
         * Oldest usage rows beyond this count are pruned. Both the pruning and the cost
         * summary are done in the database, so a large table costs memory rather than time.
         */
        private int maxEntries = 1_000_000;

        /**
         * Price list, most specific first: the first entry whose pattern matches the model
         * wins. Empty by default on purpose - published prices change, and a wrong number
         * silently produces a wrong total, which is worse than no total at all.
         */
        private List<Price> pricing = new java.util.ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getCurrency() { return currency; }
        public void setCurrency(String v) { this.currency = v; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int v) { this.maxEntries = v; }
        public List<Price> getPricing() { return pricing; }
        public void setPricing(List<Price> v) { this.pricing = v == null ? new java.util.ArrayList<>() : v; }
    }

    /** Prices are per one million tokens, the unit every vendor quotes. */
    public static class Price {

        private String modelPattern;
        private java.math.BigDecimal input;
        private java.math.BigDecimal output;
        private java.math.BigDecimal cacheRead;
        private java.math.BigDecimal cacheWrite;

        public String getModelPattern() { return modelPattern; }
        public void setModelPattern(String v) { this.modelPattern = v; }
        public java.math.BigDecimal getInput() { return input; }
        public void setInput(java.math.BigDecimal v) { this.input = v; }
        public java.math.BigDecimal getOutput() { return output; }
        public void setOutput(java.math.BigDecimal v) { this.output = v; }
        public java.math.BigDecimal getCacheRead() { return cacheRead; }
        public void setCacheRead(java.math.BigDecimal v) { this.cacheRead = v; }
        public java.math.BigDecimal getCacheWrite() { return cacheWrite; }
        public void setCacheWrite(java.math.BigDecimal v) { this.cacheWrite = v; }
    }

    /** AWS SigV4 signing settings for one provider. */
    public static class SigV4 {

        private boolean enabled = false;

        /** Signing region. Defaults to the region in the target host, then the AWS chain. */
        private String region;

        /** Signing service name. Defaults to {@code bedrock} for the Bedrock provider. */
        private String service;

        /**
         * Explicit credentials. Left unset, the standard AWS provider chain is used, so
         * environment variables, a profile or an instance role all work unchanged.
         */
        private String accessKeyId;
        private String secretAccessKey;
        private String sessionToken;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { this.region = v; }
        public String getService() { return service; }
        public void setService(String v) { this.service = v; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String v) { this.accessKeyId = v; }
        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String v) { this.secretAccessKey = v; }
        public String getSessionToken() { return sessionToken; }
        public void setSessionToken(String v) { this.sessionToken = v; }
    }

    /** Settings for {@link MockMode#REPLAY}. */
    public static class Replay {

        /** What to do when no recording matches the request. */
        public enum Fallback {
            /** Answer from the stub engine, as in {@link MockMode#MOCK}. */
            MOCK,
            /** Fail, so a missing recording is visible rather than silently substituted. */
            NOT_FOUND
        }

        private Fallback fallback = Fallback.MOCK;

        public Fallback getFallback() { return fallback; }
        public void setFallback(Fallback v) { this.fallback = v; }
    }

    public static class Stream {
        /** Words emitted per streamed chunk. */
        private int wordsPerChunk = 3;
        /** Pause between chunks. Zero keeps tests fast; raise it to exercise timeouts. */
        private long delayMs = 0;

        public int getWordsPerChunk() { return wordsPerChunk; }
        public void setWordsPerChunk(int v) { this.wordsPerChunk = v; }
        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long v) { this.delayMs = v; }
    }

    public static class Recording {
        private boolean enabled = true;
        /** Oldest records beyond this count are pruned so a long run cannot exhaust memory. */
        private int maxEntries = 1000;
        private boolean captureRequestBody = true;
        /** Upper bound on the bytes buffered per request body. */
        private int maxBodyBytes = 256 * 1024;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int v) { this.maxEntries = v; }
        public boolean isCaptureRequestBody() { return captureRequestBody; }
        public void setCaptureRequestBody(boolean v) { this.captureRequestBody = v; }
        public int getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(int v) { this.maxBodyBytes = v; }
    }

    /** Catalogue returned by the various "list models" endpoints. */
    public static class Models {
        private List<String> openai = List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1", "o3-mini");
        private List<String> anthropic =
                List.of("claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5");
        private List<String> gemini =
                List.of("gemini-2.5-pro", "gemini-2.5-flash", "text-embedding-004");
        private List<String> bedrock = List.of(
                "anthropic.claude-sonnet-4-5-20250929-v1:0",
                "amazon.nova-pro-v1:0",
                "amazon.titan-text-express-v1",
                "meta.llama3-70b-instruct-v1:0");

        public List<String> getOpenai() { return openai; }
        public void setOpenai(List<String> v) { this.openai = v; }
        public List<String> getAnthropic() { return anthropic; }
        public void setAnthropic(List<String> v) { this.anthropic = v; }
        public List<String> getGemini() { return gemini; }
        public void setGemini(List<String> v) { this.gemini = v; }
        public List<String> getBedrock() { return bedrock; }
        public void setBedrock(List<String> v) { this.bedrock = v; }
    }

    public static class Embedding {
        private int openaiDimensions = 1536;
        private int geminiDimensions = 768;

        public int getOpenaiDimensions() { return openaiDimensions; }
        public void setOpenaiDimensions(int v) { this.openaiDimensions = v; }
        public int getGeminiDimensions() { return geminiDimensions; }
        public void setGeminiDimensions(int v) { this.geminiDimensions = v; }
    }
}
