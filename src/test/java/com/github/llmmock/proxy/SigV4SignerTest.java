package com.github.llmmock.proxy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.llmmock.config.LlmMockProperties;
import com.github.llmmock.core.Provider;
import com.github.llmmock.support.SigV4Verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signs requests and checks them with {@link SigV4Verifier}, an implementation written from
 * the signing specification rather than from the SDK that produces the signature. Two
 * implementations agreeing is what makes these assertions worth anything.
 */
class SigV4SignerTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final byte[] BODY = """
            {"messages":[{"role":"user","content":[{"text":"Hello"}]}]}"""
            .getBytes(StandardCharsets.UTF_8);

    private SigV4Signer signerWith(java.util.function.Consumer<LlmMockProperties.SigV4> customiser) {
        LlmMockProperties properties = new LlmMockProperties();
        LlmMockProperties.SigV4 config = new LlmMockProperties.SigV4();
        config.setEnabled(true);
        config.setAccessKeyId(ACCESS_KEY);
        config.setSecretAccessKey(SECRET_KEY);
        customiser.accept(config);
        properties.getProxy().getSigv4().put(Provider.BEDROCK, config);
        return new SigV4Signer(properties);
    }

    private SigV4Signer signer() {
        return signerWith(config -> { });
    }

    private Map<String, List<String>> headers() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of("application/json"));
        headers.put("Accept", List.of("application/json"));
        return headers;
    }

    /** Runs the signed request past the independent verifier. */
    private boolean verifies(SigV4Signer.Signed signed, String method, byte[] body) {
        return SigV4Verifier.verify(method, signed.uri().getRawPath(), signed.uri().getRawQuery(),
                signed.headers(), body, SECRET_KEY);
    }

    @Test
    void aSignedRequestValidatesAgainstAnIndependentImplementation() {
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com"
                + "/model/amazon.nova-pro-v1/converse");

        SigV4Signer.Signed signed = signer().sign(Provider.BEDROCK, "POST", uri, headers(), BODY);

        assertThat(verifies(signed, "POST", BODY)).isTrue();
    }

    @Test
    void aModelIdContainingAColonSignsCorrectly() {
        // The case a hand-rolled signer gets wrong: the canonical URI is encoded once more
        // on top of the wire encoding, so ':' becomes %3A there but not in the request line.
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com"
                + "/model/anthropic.claude-sonnet-4-5-20250929-v1:0/converse");

        SigV4Signer.Signed signed = signer().sign(Provider.BEDROCK, "POST", uri, headers(), BODY);

        assertThat(signed.uri().getRawPath())
                .isEqualTo("/model/anthropic.claude-sonnet-4-5-20250929-v1:0/converse");
        assertThat(verifies(signed, "POST", BODY)).isTrue();
    }

    @Test
    void queryParametersAreCoveredByTheSignature() {
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com"
                + "/model/amazon.nova-pro-v1/converse-stream?b=2&a=1");

        SigV4Signer.Signed signed = signer().sign(Provider.BEDROCK, "POST", uri, headers(), BODY);

        assertThat(verifies(signed, "POST", BODY)).isTrue();
    }

    @Test
    void aTamperedBodyNoLongerValidates() {
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/m/converse");
        SigV4Signer.Signed signed = signer().sign(Provider.BEDROCK, "POST", uri, headers(), BODY);

        // Proves the verifier is actually checking something, not just returning true.
        assertThat(verifies(signed, "POST", "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    @Test
    void aSignatureMadeForOneHostDoesNotValidateForAnother() {
        URI original = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/m/converse");
        SigV4Signer.Signed signed = signer().sign(Provider.BEDROCK, "POST", original, headers(), BODY);

        Map<String, List<String>> movedHost = new LinkedHashMap<>(signed.headers());
        movedHost.put("Host", List.of("localhost:8080"));

        // This is exactly why a proxied request has to be signed again rather than forwarded.
        assertThat(SigV4Verifier.verify("POST", signed.uri().getRawPath(), null, movedHost, BODY,
                SECRET_KEY)).isFalse();
    }

    @Test
    void theSignatureCarriesTheExpectedScope() {
        URI uri = URI.create("https://bedrock-runtime.ap-northeast-1.amazonaws.com/model/m/converse");

        SigV4Signer.Signed signed = signer().sign(Provider.BEDROCK, "POST", uri, headers(), BODY);

        SigV4Verifier.Credential credential = SigV4Verifier.parse(
                signed.headers().get("Authorization").get(0));
        assertThat(credential.accessKeyId()).isEqualTo(ACCESS_KEY);
        // Region taken from the host, and Bedrock signs as "bedrock", not "bedrock-runtime".
        assertThat(credential.region()).isEqualTo("ap-northeast-1");
        assertThat(credential.service()).isEqualTo("bedrock");
        assertThat(credential.signedHeaders()).contains("host", "x-amz-date");
    }

    @Test
    void anExplicitRegionOverridesTheOneInTheHost() {
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/m/converse");

        SigV4Signer.Signed signed = signerWith(config -> config.setRegion("eu-central-1"))
                .sign(Provider.BEDROCK, "POST", uri, headers(), BODY);

        assertThat(SigV4Verifier.parse(signed.headers().get("Authorization").get(0)).region())
                .isEqualTo("eu-central-1");
    }

    @Test
    void temporaryCredentialsAddAndSignTheSecurityToken() {
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/m/converse");

        SigV4Signer.Signed signed = signerWith(config -> config.setSessionToken("session-token-value"))
                .sign(Provider.BEDROCK, "POST", uri, headers(), BODY);

        assertThat(signed.headers()).containsKey("X-Amz-Security-Token");
        assertThat(SigV4Verifier.parse(signed.headers().get("Authorization").get(0)).signedHeaders())
                .contains("x-amz-security-token");
        assertThat(verifies(signed, "POST", BODY)).isTrue();
    }

    @Test
    void theCallersOwnSignatureIsDiscardedRatherThanSigned() {
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/m/converse");
        Map<String, List<String>> withStaleSignature = headers();
        withStaleSignature.put("Authorization", List.of("AWS4-HMAC-SHA256 Credential=stale/..."));
        withStaleSignature.put("X-Amz-Date", List.of("19700101T000000Z"));
        withStaleSignature.put("X-Amz-Content-Sha256", List.of("deadbeef"));

        SigV4Signer.Signed signed =
                signer().sign(Provider.BEDROCK, "POST", uri, withStaleSignature, BODY);

        assertThat(signed.headers().get("Authorization").get(0)).doesNotContain("stale");
        assertThat(signed.headers().get("X-Amz-Date").get(0)).isNotEqualTo("19700101T000000Z");
        assertThat(verifies(signed, "POST", BODY)).isTrue();
    }

    @Test
    void signingIsRefusedWhenItIsNotEnabledForTheProvider() {
        assertThatThrownBy(() -> signer().sign(Provider.OPENAI, "POST",
                URI.create("https://api.openai.com/v1/chat/completions"), headers(), BODY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not enabled");
    }

    @Test
    void regionIsRecognisedInTypicalAwsHostnames() {
        assertThat(SigV4Signer.regionFromHost("bedrock-runtime.us-east-1.amazonaws.com"))
                .isEqualTo("us-east-1");
        assertThat(SigV4Signer.regionFromHost("bedrock-runtime.ap-northeast-1.amazonaws.com"))
                .isEqualTo("ap-northeast-1");
        assertThat(SigV4Signer.regionFromHost("bedrock-runtime-fips.us-gov-west-1.amazonaws.com"))
                .isEqualTo("us-gov-west-1");
        assertThat(SigV4Signer.regionFromHost("localhost")).isNull();
    }
}
