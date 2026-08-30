package io.github.ykwyuta.llmmock.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent decoder for the {@code application/vnd.amazon.eventstream} framing, written
 * against the published layout rather than against the encoder. It verifies both CRCs, so
 * a frame the AWS SDKs would reject fails the test here too.
 */
public final class EventStreamDecoder {

    private EventStreamDecoder() {
    }

    public record Frame(Map<String, String> headers, String payload) {

        public String eventType() {
            return headers.get(":event-type");
        }
    }

    public static List<Frame> decode(byte[] data) {
        List<Frame> frames = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.remaining() > 0) {
            int frameStart = buffer.position();
            assertThat(buffer.remaining())
                    .as("a frame needs at least a 12 byte prelude and a 4 byte trailing CRC")
                    .isGreaterThanOrEqualTo(16);

            int totalLength = buffer.getInt();
            int headersLength = buffer.getInt();
            int preludeCrc = buffer.getInt();

            CRC32 expectedPreludeCrc = new CRC32();
            expectedPreludeCrc.update(data, frameStart, 8);
            assertThat(preludeCrc).as("prelude CRC32").isEqualTo((int) expectedPreludeCrc.getValue());

            byte[] headerBytes = new byte[headersLength];
            buffer.get(headerBytes);
            int payloadLength = totalLength - 12 - headersLength - 4;
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);

            int messageCrc = buffer.getInt();
            CRC32 expectedMessageCrc = new CRC32();
            expectedMessageCrc.update(data, frameStart, totalLength - 4);
            assertThat(messageCrc).as("message CRC32").isEqualTo((int) expectedMessageCrc.getValue());

            frames.add(new Frame(decodeHeaders(headerBytes),
                    new String(payload, StandardCharsets.UTF_8)));
        }
        return frames;
    }

    private static Map<String, String> decodeHeaders(byte[] headerBytes) {
        Map<String, String> headers = new LinkedHashMap<>();
        ByteBuffer buffer = ByteBuffer.wrap(headerBytes);
        while (buffer.remaining() > 0) {
            int nameLength = Byte.toUnsignedInt(buffer.get());
            byte[] name = new byte[nameLength];
            buffer.get(name);
            int valueType = Byte.toUnsignedInt(buffer.get());
            assertThat(valueType).as("only the string header type is used here").isEqualTo(7);
            int valueLength = Short.toUnsignedInt(buffer.getShort());
            byte[] value = new byte[valueLength];
            buffer.get(value);
            headers.put(new String(name, StandardCharsets.UTF_8),
                    new String(value, StandardCharsets.UTF_8));
        }
        return headers;
    }
}
