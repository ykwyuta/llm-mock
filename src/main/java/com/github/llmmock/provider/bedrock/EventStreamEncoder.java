package com.github.llmmock.provider.bedrock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Encoder for the {@code application/vnd.amazon.eventstream} framing that Bedrock's
 * streaming operations use. The AWS SDKs decode this format rather than SSE, so a mock
 * that emitted plain JSON lines would not be usable from a real client.
 *
 * <p>Frame layout, all integers big-endian:
 * <pre>
 * uint32 totalLength      whole frame including both CRCs
 * uint32 headersLength    bytes of the encoded header block
 * uint32 preludeCrc       CRC32 of the preceding 8 bytes
 * bytes  headers          repeated: uint8 nameLen, name, uint8 valueType, value
 * bytes  payload          totalLength - headersLength - 16 bytes
 * uint32 messageCrc       CRC32 of every preceding byte of this frame
 * </pre>
 */
public final class EventStreamEncoder {

    /** Header value type 7: a UTF-8 string prefixed with its uint16 length. */
    private static final byte HEADER_TYPE_STRING = 7;

    private static final int PRELUDE_LENGTH = 12;
    private static final int MESSAGE_CRC_LENGTH = 4;

    private EventStreamEncoder() {
    }

    /** Writes one {@code event}-typed frame carrying a JSON payload. */
    public static void writeEvent(OutputStream out, String eventType, byte[] payload)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":event-type", eventType);
        headers.put(":content-type", "application/json");
        headers.put(":message-type", "event");
        writeFrame(out, headers, payload);
    }

    /** Writes an {@code exception}-typed frame, which is how Bedrock reports mid-stream failures. */
    public static void writeException(OutputStream out, String exceptionType, byte[] payload)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":exception-type", exceptionType);
        headers.put(":content-type", "application/json");
        headers.put(":message-type", "exception");
        writeFrame(out, headers, payload);
    }

    public static void writeFrame(OutputStream out, Map<String, String> headers, byte[] payload)
            throws IOException {
        byte[] headerBytes = encodeHeaders(headers);
        int totalLength = PRELUDE_LENGTH + headerBytes.length + payload.length + MESSAGE_CRC_LENGTH;

        ByteBuffer prelude = ByteBuffer.allocate(8);
        prelude.putInt(totalLength);
        prelude.putInt(headerBytes.length);

        CRC32 preludeCrc = new CRC32();
        preludeCrc.update(prelude.array());

        ByteArrayOutputStream frame = new ByteArrayOutputStream(totalLength);
        frame.write(prelude.array());
        frame.write(intToBytes((int) preludeCrc.getValue()));
        frame.write(headerBytes);
        frame.write(payload);

        CRC32 messageCrc = new CRC32();
        messageCrc.update(frame.toByteArray());
        frame.write(intToBytes((int) messageCrc.getValue()));

        out.write(frame.toByteArray());
        out.flush();
    }

    private static byte[] encodeHeaders(Map<String, String> headers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            byte[] name = header.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = header.getValue().getBytes(StandardCharsets.UTF_8);
            if (name.length > 255) {
                throw new IOException("Header name too long: " + header.getKey());
            }
            out.write(name.length);
            out.write(name);
            out.write(HEADER_TYPE_STRING);
            out.write((value.length >>> 8) & 0xFF);
            out.write(value.length & 0xFF);
            out.write(value);
        }
        return out.toByteArray();
    }

    private static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }
}
