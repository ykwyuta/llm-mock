package com.example.llmmock.core;

/** How the server should answer a provider request. */
public enum MockMode {

    /** Answer from the stub engine. The default. */
    MOCK,

    /**
     * Forward to the real upstream API, return its answer untouched, and write the
     * exchange to a recording file for later replay.
     */
    PROXY,

    /**
     * Answer from previously recorded files, byte for byte. Requests with no matching
     * recording fall back according to {@code llm-mock.replay.fallback}.
     */
    REPLAY,

    /**
     * Proxy, but answer from the recording when the same request has been seen before.
     * A repeated request costs nothing and returns instantly; a new one goes upstream once
     * and is recorded, so a suite converges on a complete set of fixtures by being run.
     */
    CACHED_PROXY
}
