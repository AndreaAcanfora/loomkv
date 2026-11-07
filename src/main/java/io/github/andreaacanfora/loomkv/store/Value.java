package io.github.andreaacanfora.loomkv.store;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** What a key holds. Sealed: commands switch over it, and the compiler checks every type is handled. */
public sealed interface Value {

    record StringValue(byte[] data) implements Value {}

    record ListValue(ArrayDeque<byte[]> items) implements Value {
        public ListValue() {
            this(new ArrayDeque<>());
        }
    }

    record HashValue(LinkedHashMap<Bytes, byte[]> fields) implements Value {
        public HashValue() {
            this(new LinkedHashMap<>());
        }
    }

    record SetValue(LinkedHashSet<Bytes> members) implements Value {
        public SetValue() {
            this(new LinkedHashSet<>());
        }
    }

    /** The name TYPE returns. */
    default String typeName() {
        return switch (this) {
            case StringValue _ -> "string";
            case ListValue _ -> "list";
            case HashValue _ -> "hash";
            case SetValue _ -> "set";
        };
    }

    /** Empty collections are deleted, as in Redis. */
    default boolean isEmpty() {
        return switch (this) {
            case StringValue _ -> false;
            case ListValue list -> list.items().isEmpty();
            case HashValue hash -> hash.fields().isEmpty();
            case SetValue set -> set.members().isEmpty();
        };
    }
}
