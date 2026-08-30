package com.example.llmmock.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything about the mock's behaviour that a test suite may want to retune. */
@ConfigurationProperties(prefix = "llm-mock")
public class LlmMockProperties {

    private final Paths paths = new Paths();
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

    public Paths getPaths() { return paths; }
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
