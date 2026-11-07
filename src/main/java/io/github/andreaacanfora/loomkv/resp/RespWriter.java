package io.github.andreaacanfora.loomkv.resp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Encodes replies as RESP2. */
public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NIL_BULK = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NIL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);

    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out;
    }

    public void write(RespValue value) throws IOException {
        switch (value) {
            case RespValue.SimpleString(String text) -> line('+', text);
            case RespValue.ErrorReply(String message) -> line('-', message);
            case RespValue.IntegerReply(long number) -> line(':', Long.toString(number));
            case RespValue.BulkString(byte[] data) when data == null -> out.write(NIL_BULK);
            case RespValue.BulkString(byte[] data) -> {
                line('$', Integer.toString(data.length));
                out.write(data);
                out.write(CRLF);
            }
            case RespValue.ArrayReply(var items) when items == null -> out.write(NIL_ARRAY);
            case RespValue.ArrayReply(var items) -> {
                line('*', Integer.toString(items.size()));
                for (RespValue item : items) {
                    write(item);
                }
            }
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    private void line(char type, String text) throws IOException {
        out.write(type);
        // Status and error lines must not break the framing.
        out.write(text.replace('\r', ' ').replace('\n', ' ').getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }
}
