package io.github.ykwyuta.llmmock.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a finished answer into the pieces a streaming response emits. Splitting happens
 * on word boundaries with the whitespace kept on the preceding chunk, so concatenating
 * every chunk reproduces the original text exactly.
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> chunk(String text, int wordsPerChunk) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        int size = Math.max(1, wordsPerChunk);
        List<String> words = splitKeepingWhitespace(text);
        for (int i = 0; i < words.size(); i += size) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < Math.min(i + size, words.size()); j++) {
                sb.append(words.get(j));
            }
            chunks.add(sb.toString());
        }
        return chunks;
    }

    /** Each element is one word plus any whitespace that immediately follows it. */
    private static List<String> splitKeepingWhitespace(String text) {
        List<String> parts = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int wordEnd = index;
            while (wordEnd < text.length() && !Character.isWhitespace(text.charAt(wordEnd))) {
                wordEnd++;
            }
            int spaceEnd = wordEnd;
            while (spaceEnd < text.length() && Character.isWhitespace(text.charAt(spaceEnd))) {
                spaceEnd++;
            }
            parts.add(text.substring(index, spaceEnd));
            index = spaceEnd;
        }
        return parts;
    }
}
