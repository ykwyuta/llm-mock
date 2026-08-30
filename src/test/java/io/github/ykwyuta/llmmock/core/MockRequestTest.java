package io.github.ykwyuta.llmmock.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockRequestTest {

    @Test
    void lastUserTextIgnoresTrailingNonUserTurns() {
        MockRequest request = MockRequest.builder(Provider.ANTHROPIC, "messages")
                .model("claude-sonnet-4-5")
                .message(ChatRole.USER, "first question")
                .message(ChatRole.ASSISTANT, "an answer")
                .message(ChatRole.USER, "second question")
                .message(ChatRole.TOOL, "tool output")
                .build();

        assertThat(request.lastUserText()).isEqualTo("second question");
    }

    @Test
    void conversationTextIsWhatPromptPatternsMatchAgainst() {
        MockRequest request = MockRequest.builder(Provider.OPENAI, "chat.completions")
                .model("gpt-4o")
                .message(ChatRole.SYSTEM, "be terse")
                .message(ChatRole.USER, "hi")
                .build();

        assertThat(request.conversationText()).isEqualTo("system: be terse\nuser: hi");
    }

    @Test
    void blankMessagesAreDropped() {
        MockRequest request = MockRequest.builder(Provider.GEMINI, "generateContent")
                .model("gemini-2.5-pro")
                .message(ChatRole.SYSTEM, "")
                .message(ChatRole.SYSTEM, null)
                .message(ChatRole.USER, "hello")
                .build();

        assertThat(request.messages()).hasSize(1);
        assertThat(request.lastUserText()).isEqualTo("hello");
    }
}
