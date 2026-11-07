package io.github.andreaacanfora.loomkv.resp;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A RESP2 reply. The type is sealed, so a switch over it is checked for completeness by the compiler.
 */
public sealed interface RespValue {

    /** {@code +OK}: short status text without newlines. */
    record SimpleString(String value) implements RespValue {}

    /** {@code -ERR ...}: the first word is the error code (ERR, WRONGTYPE, ...). */
    record ErrorReply(String message) implements RespValue {}

    /** {@code :42}. */
    record IntegerReply(long value) implements RespValue {}

    /** {@code $3\r\nfoo}; a null value is the RESP nil bulk string. */
    record BulkString(byte[] value) implements RespValue {}

    /** {@code *2\r\n...}; a null list is the RESP nil array. */
    record ArrayReply(List<RespValue> values) implements RespValue {}

    RespValue OK = new SimpleString("OK");
    RespValue PONG = new SimpleString("PONG");
    RespValue NIL = new BulkString(null);
    RespValue NIL_ARRAY = new ArrayReply(null);
    RespValue ZERO = new IntegerReply(0);
    RespValue ONE = new IntegerReply(1);
    RespValue EMPTY_ARRAY = new ArrayReply(List.of());

    static RespValue bulk(byte[] value) {
        return new BulkString(value);
    }

    static RespValue bulk(String value) {
        return new BulkString(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    static RespValue integer(long value) {
        return value == 0 ? ZERO : value == 1 ? ONE : new IntegerReply(value);
    }

    static RespValue bool(boolean value) {
        return value ? ONE : ZERO;
    }

    static RespValue error(String message) {
        return new ErrorReply(message);
    }

    static RespValue array(List<RespValue> values) {
        return new ArrayReply(values);
    }

    static RespValue bulkArray(List<byte[]> values) {
        return new ArrayReply(values.stream().map(RespValue::bulk).toList());
    }
}
