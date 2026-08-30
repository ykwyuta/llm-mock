package io.github.ykwyuta.llmmock.provider.common;

import tools.jackson.databind.JsonNode;

/**
 * Flattens the several shapes providers accept for message content into plain text.
 * Every provider allows either a bare string or an array of typed parts.
 */
public final class JsonText {

    private JsonText() {
    }

    public static String flatten(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return "";
        }
        if (content.isString()) {
            return content.asString();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String text = textOfPart(part);
                if (!text.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        if (content.isObject()) {
            return textOfPart(content);
        }
        return content.asString("");
    }

    private static String textOfPart(JsonNode part) {
        if (part == null) {
            return "";
        }
        if (part.isString()) {
            return part.asString();
        }
        JsonNode text = part.get("text");
        if (text != null && text.isString()) {
            return text.asString();
        }
        // Non-text parts (images, documents, tool results) contribute nothing to the prompt
        // text but must not break matching, so they are skipped rather than stringified.
        return "";
    }
}
