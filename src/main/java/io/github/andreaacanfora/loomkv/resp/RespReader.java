package io.github.andreaacanfora.loomkv.resp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads client requests: RESP arrays of bulk strings ({@code *2\r\n$3\r\nGET\r\n$1\r\nk\r\n}), or inline
 * commands typed by hand ({@code PING} over telnet, or redis-benchmark's PING_INLINE).
 */
public final class RespReader {

    static final int MAX_BULK = 512 * 1024 * 1024;
    static final int MAX_ARGS = 1024 * 1024;
    static final int MAX_INLINE = 64 * 1024;

    private final InputStream in;

    public RespReader(InputStream in) {
        this.in = in;
    }

    /** True if more bytes are ready without blocking: lets the server batch pipelined replies. */
    public boolean hasBuffered() throws IOException {
        return in.available() > 0;
    }

    /** The next command's arguments, or null at end of stream. */
    public List<byte[]> readCommand() throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }
        if (first != '*') {
            return readInline(first);
        }
        long count = readNumber();
        if (count <= 0) {
            return List.of();
        }
        if (count > MAX_ARGS) {
            throw new ProtocolException("invalid multibulk length");
        }
        List<byte[]> args = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            int marker = in.read();
            if (marker != '$') {
                throw new ProtocolException("expected '$', got '" + printable(marker) + "'");
            }
            long length = readNumber();
            if (length < 0 || length > MAX_BULK) {
                throw new ProtocolException("invalid bulk length");
            }
            byte[] data = in.readNBytes((int) length);
            if (data.length != length) {
                return null;
            }
            expectCrlf();
            args.add(data);
        }
        return args;
    }

    private List<byte[]> readInline(int first) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b = first;
        while (b != '\n') {
            if (b == -1) {
                return null;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > MAX_INLINE) {
                throw new ProtocolException("too big inline request");
            }
            b = in.read();
        }
        String text = line.toString(StandardCharsets.UTF_8).strip();
        List<byte[]> args = new ArrayList<>();
        for (String part : text.split("\\s+")) {
            if (!part.isEmpty()) {
                args.add(part.getBytes(StandardCharsets.UTF_8));
            }
        }
        return args;
    }

    private long readNumber() throws IOException {
        long value = 0;
        boolean negative = false;
        int b = in.read();
        if (b == '-') {
            negative = true;
            b = in.read();
        }
        int digits = 0;
        while (b >= '0' && b <= '9') {
            value = value * 10 + (b - '0');
            if (++digits > 18) {
                throw new ProtocolException("number too long");
            }
            b = in.read();
        }
        if (digits == 0 || b != '\r' || in.read() != '\n') {
            throw new ProtocolException("invalid length");
        }
        return negative ? -value : value;
    }

    private void expectCrlf() throws IOException {
        if (in.read() != '\r' || in.read() != '\n') {
            throw new ProtocolException("expected CRLF after bulk string");
        }
    }

    private static String printable(int b) {
        return b == -1 ? "EOF" : String.valueOf((char) b);
    }
}
