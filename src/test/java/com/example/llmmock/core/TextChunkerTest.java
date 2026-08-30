package com.example.llmmock.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    @Test
    void concatenatingChunksReproducesTheOriginalText() {
        String text = "The quick brown fox  jumps\nover the lazy dog";

        for (int size = 1; size <= 6; size++) {
            assertThat(String.join("", TextChunker.chunk(text, size)))
                    .as("chunk size %d", size)
                    .isEqualTo(text);
        }
    }

    @Test
    void groupsWordsAndKeepsTrailingWhitespaceOnThePrecedingChunk() {
        List<String> chunks = TextChunker.chunk("one two three four five", 2);

        assertThat(chunks).containsExactly("one two ", "three four ", "five");
    }

    @Test
    void emptyAndNullTextProduceNoChunks() {
        assertThat(TextChunker.chunk("", 3)).isEmpty();
        assertThat(TextChunker.chunk(null, 3)).isEmpty();
    }

    @Test
    void nonPositiveChunkSizeFallsBackToOneWordPerChunk() {
        assertThat(TextChunker.chunk("a b c", 0)).containsExactly("a ", "b ", "c");
    }
}
