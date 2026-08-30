package io.github.ykwyuta.llmmock.provider.bedrock;

/**
 * Model families that {@code InvokeModel} accepts. Unlike Converse, InvokeModel passes the
 * model's own native payload straight through, so the mock has to pick a body shape from
 * the model id.
 */
public enum BedrockFamily {
    ANTHROPIC,
    TITAN,
    NOVA,
    LLAMA;

    public static BedrockFamily of(String modelId) {
        if (modelId == null) {
            return ANTHROPIC;
        }
        String id = modelId.toLowerCase();
        // Cross-region inference profiles prefix the id with a region, e.g. "us.anthropic...".
        if (id.contains("amazon.titan")) {
            return TITAN;
        }
        if (id.contains("amazon.nova")) {
            return NOVA;
        }
        if (id.contains("meta.llama")) {
            return LLAMA;
        }
        // Anthropic is both the most common family on Bedrock and the safest default.
        return ANTHROPIC;
    }
}
