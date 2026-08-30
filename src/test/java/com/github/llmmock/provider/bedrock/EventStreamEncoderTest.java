package com.github.llmmock.provider.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.llmmock.support.EventStreamDecoder;

import static org.assertj.core.api.Assertions.assertThat;

class EventStreamEncoderTest {

    @Test
    void encodesAFrameThatRoundTripsThroughAnIndependentDecoder() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        EventStreamEncoder.writeEvent(out, "contentBlockDelta",
                "{\"delta\":{\"text\":\"hi\"}}".getBytes(StandardCharsets.UTF_8));

        // The decoder asserts both CRCs, so reaching here means the framing is valid.
        List<EventStreamDecoder.Frame> frames = EventStreamDecoder.decode(out.toByteArray());
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).headers())
                .containsEntry(":event-type", "contentBlockDelta")
                .containsEntry(":content-type", "application/json")
                .containsEntry(":message-type", "event");
        assertThat(frames.get(0).payload()).isEqualTo("{\"delta\":{\"text\":\"hi\"}}");
    }

    @Test
    void framesConcatenateWithoutSeparators() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        EventStreamEncoder.writeEvent(out, "messageStart", "{\"role\":\"assistant\"}".getBytes(StandardCharsets.UTF_8));
        EventStreamEncoder.writeEvent(out, "messageStop", "{\"stopReason\":\"end_turn\"}".getBytes(StandardCharsets.UTF_8));

        List<EventStreamDecoder.Frame> frames = EventStreamDecoder.decode(out.toByteArray());
        assertThat(frames).extracting(EventStreamDecoder.Frame::eventType)
                .containsExactly("messageStart", "messageStop");
    }

    @Test
    void exceptionFramesAreTaggedAsSuch() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        EventStreamEncoder.writeException(out, "throttlingException",
                "{\"message\":\"slow down\"}".getBytes(StandardCharsets.UTF_8));

        EventStreamDecoder.Frame frame = EventStreamDecoder.decode(out.toByteArray()).get(0);
        assertThat(frame.headers())
                .containsEntry(":exception-type", "throttlingException")
                .containsEntry(":message-type", "exception");
    }

    @Test
    void anEmptyPayloadStillProducesAWellFormedFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        EventStreamEncoder.writeEvent(out, "contentBlockStop", new byte[0]);

        assertThat(EventStreamDecoder.decode(out.toByteArray())).singleElement()
                .satisfies(frame -> assertThat(frame.payload()).isEmpty());
    }
}
