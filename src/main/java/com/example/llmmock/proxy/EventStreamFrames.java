package com.example.llmmock.proxy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal reader for the {@code application/vnd.amazon.eventstream} framing, used to pull
 * token counts back out of a proxied Bedrock stream.
 *
 * <p>It does not verify the CRCs: these bytes came from the upstream and have already been
 * handed to the caller, so the question here is only "what is in them". The verifying
 * decoder that proves {@link EventStreamEncoder} produces valid frames lives in the tests
 * and is deliberately a separate implementation.
 */
public final class EventStreamFrames {

    private static final int PRELUDE_LENGTH = 12;
    private static final int MESSAGE_CRC_LENGTH = 4;
    private static final byte HEADER_TYPE_STRING = 7;

    private EventStreamFrames() {
    }

    public record Frame(String eventType, String payload) {
    }

    /** Returns the frames, or an empty list if the bytes are not a well-formed stream. */
    public static List<Frame> parse(byte[] data) {
        List<Frame> frames = new ArrayList<>();
        if (data == null) {
            return frames;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.remaining() >= PRELUDE_LENGTH + MESSAGE_CRC_LENGTH) {
                int start = buffer.position();
                int totalLength = buffer.getInt();
                int headersLength = buffer.getInt();
                buffer.getInt(); // prelude CRC, not checked here
                int payloadLength = totalLength - PRELUDE_LENGTH - headersLength - MESSAGE_CRC_LENGTH;
                if (totalLength <= 0 || headersLength < 0 || payloadLength < 0
                        || start + totalLength > data.length) {
                    return frames;
                }
                byte[] headerBytes = new byte[headersLength];
                buffer.get(headerBytes);
                byte[] payload = new byte[payloadLength];
                buffer.get(payload);
                buffer.getInt(); // message CRC, not checked here

                frames.add(new Frame(eventType(headerBytes),
                        new String(payload, StandardCharsets.UTF_8)));
            }
        } catch (RuntimeException ex) {
            // A truncated or unexpected stream yields whatever was readable rather than
            // failing the request that carried it.
            return frames;
        }
        return frames;
    }

    private static String eventType(byte[] headerBytes) {
        ByteBuffer buffer = ByteBuffer.wrap(headerBytes);
        while (buffer.remaining() > 0) {
            int nameLength = Byte.toUnsignedInt(buffer.get());
            byte[] name = new byte[nameLength];
            buffer.get(name);
            int valueType = Byte.toUnsignedInt(buffer.get());
            if (valueType != HEADER_TYPE_STRING) {
                return null;
            }
            int valueLength = Short.toUnsignedInt(buffer.getShort());
            byte[] value = new byte[valueLength];
            buffer.get(value);
            if (":event-type".equals(new String(name, StandardCharsets.UTF_8))) {
                return new String(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
