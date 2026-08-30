package io.github.ykwyuta.llmmock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider neutral view of an inbound completion request. Every provider adapter
 * translates its own payload into this shape before the engine sees it, which is what
 * lets one stub rule serve all four protocols.
 */
public record MockRequest(
        Provider provider,
        String endpoint,
        String model,
        List<ChatMessage> messages,
        Integer maxTokens,
        Double temperature,
        Double topP,
        List<String> stopSequences,
        boolean stream,
        List<ToolSpec> tools) {

    public MockRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** Text of the last user turn, which is what the default response template echoes. */
    public String lastUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatRole.USER) {
                return messages.get(i).text();
            }
        }
        return messages.isEmpty() ? "" : messages.get(messages.size() - 1).text();
    }

    /** All turns joined with newlines. Stub {@code promptPattern}s are matched against this. */
    public String conversationText() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(m.role().name().toLowerCase()).append(": ").append(m.text());
        }
        return sb.toString();
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    public static Builder builder(Provider provider, String endpoint) {
        return new Builder(provider, endpoint);
    }

    public static final class Builder {
        private final Provider provider;
        private final String endpoint;
        private String model;
        private final List<ChatMessage> messages = new ArrayList<>();
        private Integer maxTokens;
        private Double temperature;
        private Double topP;
        private List<String> stopSequences = List.of();
        private boolean stream;
        private final List<ToolSpec> tools = new ArrayList<>();

        private Builder(Provider provider, String endpoint) {
            this.provider = provider;
            this.endpoint = endpoint;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder message(ChatRole role, String text) {
            if (text != null && !text.isEmpty()) {
                this.messages.add(ChatMessage.of(role, text));
            }
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences == null ? List.of() : stopSequences;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder tool(ToolSpec tool) {
            if (tool != null) {
                this.tools.add(tool);
            }
            return this;
        }

        public MockRequest build() {
            return new MockRequest(provider, endpoint, model, messages, maxTokens, temperature, topP,
                    stopSequences, stream, tools);
        }
    }
}
