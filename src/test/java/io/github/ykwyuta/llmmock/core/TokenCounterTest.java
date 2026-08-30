package io.github.ykwyuta.llmmock.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCounterTest {

    private final TokenCounter counter = new TokenCounter();

    @Test
    void countsRoughlyFourCharactersPerToken() {
        assertThat(counter.countText("abcd")).isEqualTo(1);
        assertThat(counter.countText("abcde")).isEqualTo(2);
        assertThat(counter.countText("")).isZero();
        assertThat(counter.countText(null)).isZero();
    }

    @Test
    void requestCountAddsAPerMessageOverhead() {
        MockRequest request = MockRequest.builder(Provider.OPENAI, "chat.completions")
                .model("gpt-4o")
                .message(ChatRole.USER, "abcd")
                .build();

        // 3 tokens of message overhead plus 1 token for the four characters.
        assertThat(counter.countRequest(request)).isEqualTo(4);
    }

    @Test
    void countsAreStableAcrossCalls() {
        MockRequest request = MockRequest.builder(Provider.GEMINI, "generateContent")
                .model("gemini-2.5-pro")
                .message(ChatRole.SYSTEM, "be terse")
                .message(ChatRole.USER, "hello world")
                .build();

        assertThat(List.of(counter.countRequest(request), counter.countRequest(request)))
                .containsExactly(counter.countRequest(request), counter.countRequest(request));
    }
}
