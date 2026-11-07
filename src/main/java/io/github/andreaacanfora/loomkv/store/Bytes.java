package io.github.andreaacanfora.loomkv.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A binary-safe key or member: Redis keys are byte strings, not Java strings. */
public final class Bytes implements Comparable<Bytes> {

    private final byte[] data;
    private final int hash;

    public Bytes(byte[] data) {
        this.data = data;
        this.hash = Arrays.hashCode(data);
    }

    public static Bytes of(String text) {
        return new Bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes() {
        return data;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Bytes that && hash == that.hash && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int compareTo(Bytes other) {
        return Arrays.compareUnsigned(data, other.data);
    }

    @Override
    public String toString() {
        return new String(data, StandardCharsets.UTF_8);
    }
}
