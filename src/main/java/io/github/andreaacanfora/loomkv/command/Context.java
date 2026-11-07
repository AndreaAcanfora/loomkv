package io.github.andreaacanfora.loomkv.command;

import io.github.andreaacanfora.loomkv.store.Bytes;
import io.github.andreaacanfora.loomkv.store.Database;
import io.github.andreaacanfora.loomkv.store.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One command being executed: its arguments, the database, and what to write to the append-only file. */
public final class Context {

    private final Database db;
    private final Broker broker;
    private final List<byte[]> args;
    private List<List<byte[]>> propagated;
    private boolean propagateNothing;

    public Context(Database db, Broker broker, List<byte[]> args) {
        this.db = db;
        this.broker = broker;
        this.args = args;
    }

    public Database db() {
        return db;
    }

    public Broker broker() {
        return broker;
    }

    public List<byte[]> args() {
        return args;
    }

    public int argc() {
        return args.size();
    }

    public byte[] arg(int i) {
        return args.get(i);
    }

    public Bytes key(int i) {
        return new Bytes(args.get(i));
    }

    /** An argument as an uppercase keyword, for options like EX or NX. */
    public String keyword(int i) {
        return new String(args.get(i), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
    }

    public long longArg(int i) throws CommandException {
        return parseLong(args.get(i));
    }

    public static long parseLong(byte[] data) throws CommandException {
        try {
            return Long.parseLong(new String(data, StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            throw new CommandException(CommandException.NOT_INTEGER);
        }
    }

    /** The value at a key, checked to be of the expected type; null if the key is missing. */
    public <T extends Value> T typed(Bytes key, Class<T> type) throws CommandException {
        Value value = db.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new CommandException(CommandException.WRONGTYPE);
        }
        return type.cast(value);
    }

    /**
     * Replaces what is written to the append-only file for this command. Used to turn relative times
     * (EXPIRE 10) into absolute ones (PEXPIREAT ...), so replaying the file later gives the same result.
     */
    public void propagate(byte[]... command) {
        if (propagated == null) {
            propagated = new ArrayList<>();
        }
        propagated.add(List.of(command));
    }

    /** Nothing to persist (for example SET ... NX that did not set anything). */
    public void propagateNothing() {
        propagateNothing = true;
    }

    /** The commands to append to the AOF for this execution. */
    List<List<byte[]>> toPersist() {
        if (propagateNothing) {
            return List.of();
        }
        return propagated != null ? propagated : List.of(args);
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] number(long n) {
        return ascii(Long.toString(n));
    }
}
