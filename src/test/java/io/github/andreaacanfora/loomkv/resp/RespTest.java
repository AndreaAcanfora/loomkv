package io.github.andreaacanfora.loomkv.resp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespTest {

    private static RespReader reader(String text) {
        return new RespReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> strings(List<byte[]> args) {
        return args.stream().map(a -> new String(a, StandardCharsets.UTF_8)).toList();
    }

    @Test
    void readsPipelinedArraysAndInlineCommands() throws IOException {
        RespReader r = reader("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\nPING hello  world\r\n*1\r\n$4\r\nPING\r\n");
        assertEquals(List.of("GET", "key"), strings(r.readCommand()));
        assertEquals(List.of("PING", "hello", "world"), strings(r.readCommand()));
        assertEquals(List.of("PING"), strings(r.readCommand()));
        assertNull(r.readCommand());
    }

    @Test
    void bulkStringsAreBinarySafe() throws IOException {
        byte[] value = {0, '\r', '\n', (byte) 0xff};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("*2\r\n$3\r\nSET\r\n$4\r\n".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(value);
        out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        List<byte[]> args = new RespReader(new ByteArrayInputStream(out.toByteArray())).readCommand();
        assertArrayEquals(value, args.get(1));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(ProtocolException.class, () -> reader("*1\r\n+GET\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> reader("*x\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> reader("*1\r\n$3\r\nGETXX").readCommand());
        assertThrows(ProtocolException.class, () -> reader("*1\r\n$-5\r\n").readCommand());
    }

    @Test
    void truncatedInputIsEndOfStream() throws IOException {
        assertNull(reader("*2\r\n$3\r\nGET\r\n$10\r\nabc").readCommand());
        assertNull(reader("PING").readCommand());
    }

    @Test
    void writesEveryReplyType() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespWriter writer = new RespWriter(out);
        writer.write(RespValue.OK);
        writer.write(RespValue.error("ERR bad\r\nthing"));
        writer.write(RespValue.integer(-42));
        writer.write(RespValue.bulk("héllo"));
        writer.write(RespValue.NIL);
        writer.write(RespValue.NIL_ARRAY);
        writer.write(RespValue.array(List.of(RespValue.bulk("a"), RespValue.integer(1), RespValue.EMPTY_ARRAY)));
        writer.flush();
        assertEquals("+OK\r\n-ERR bad  thing\r\n:-42\r\n$6\r\nhéllo\r\n$-1\r\n*-1\r\n*3\r\n$1\r\na\r\n:1\r\n*0\r\n",
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void integerRepliesAreShared() {
        assertEquals(RespValue.ZERO, RespValue.integer(0));
        assertEquals(RespValue.ONE, RespValue.bool(true));
        assertEquals(Arrays.asList(2L), List.of(((RespValue.IntegerReply) RespValue.integer(2)).value()));
    }
}
