package io.github.andreaacanfora.loomkv.command;

import static io.github.andreaacanfora.loomkv.command.Context.ascii;
import static io.github.andreaacanfora.loomkv.command.Context.number;

import io.github.andreaacanfora.loomkv.resp.RespValue;
import io.github.andreaacanfora.loomkv.store.Bytes;
import io.github.andreaacanfora.loomkv.store.Glob;
import io.github.andreaacanfora.loomkv.store.Value;
import io.github.andreaacanfora.loomkv.store.Value.HashValue;
import io.github.andreaacanfora.loomkv.store.Value.ListValue;
import io.github.andreaacanfora.loomkv.store.Value.SetValue;
import io.github.andreaacanfora.loomkv.store.Value.StringValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** The command table and the dispatcher. */
public final class Commands {

    private final Map<String, CommandSpec> table = new HashMap<>();

    public Commands() {
        // Strings
        add("GET", 2, false, Commands::get);
        add("SET", -3, true, Commands::set);
        add("SETNX", 3, true, ctx -> RespValue.bool(setIfAbsent(ctx, ctx.key(1), ctx.arg(2))));
        add("SETEX", 4, true, ctx -> setWithTtl(ctx, 1000));
        add("PSETEX", 4, true, ctx -> setWithTtl(ctx, 1));
        add("GETSET", 3, true, Commands::getSet);
        add("GETDEL", 2, true, Commands::getDel);
        add("MGET", -2, false, Commands::mget);
        add("MSET", -3, true, Commands::mset);
        add("MSETNX", -3, true, Commands::msetnx);
        add("INCR", 2, true, ctx -> incrBy(ctx, 1));
        add("DECR", 2, true, ctx -> incrBy(ctx, -1));
        add("INCRBY", 3, true, ctx -> incrBy(ctx, ctx.longArg(2)));
        add("DECRBY", 3, true, ctx -> incrBy(ctx, negate(ctx.longArg(2))));
        add("APPEND", 3, true, Commands::append);
        add("STRLEN", 2, false, Commands::strlen);

        // Keys and expiry
        add("DEL", -2, true, Commands::del);
        add("UNLINK", -2, true, Commands::del);
        add("EXISTS", -2, false, Commands::exists);
        add("TYPE", 2, false, Commands::type);
        add("KEYS", 2, false, ctx -> RespValue.bulkArray(
                ctx.db().keys(ctx.arg(1)).stream().map(Bytes::bytes).toList()));
        add("DBSIZE", 1, false, ctx -> RespValue.integer(ctx.db().size()));
        add("FLUSHDB", -1, true, Commands::flush);
        add("FLUSHALL", -1, true, Commands::flush);
        add("RENAME", 3, true, Commands::rename);
        add("EXPIRE", 3, true, ctx -> expire(ctx, ctx.db().now(), 1000));
        add("PEXPIRE", 3, true, ctx -> expire(ctx, ctx.db().now(), 1));
        add("EXPIREAT", 3, true, ctx -> expire(ctx, 0, 1000));
        add("PEXPIREAT", 3, true, ctx -> expire(ctx, 0, 1));
        add("TTL", 2, false, ctx -> ttl(ctx, 1000));
        add("PTTL", 2, false, ctx -> ttl(ctx, 1));
        add("PERSIST", 2, true, ctx -> RespValue.bool(ctx.db().persist(ctx.key(1))));

        // Lists
        add("LPUSH", -3, true, ctx -> push(ctx, true));
        add("RPUSH", -3, true, ctx -> push(ctx, false));
        add("LPOP", -2, true, ctx -> pop(ctx, true));
        add("RPOP", -2, true, ctx -> pop(ctx, false));
        add("LLEN", 2, false, ctx -> {
            ListValue list = ctx.typed(ctx.key(1), ListValue.class);
            return RespValue.integer(list == null ? 0 : list.items().size());
        });
        add("LRANGE", 4, false, Commands::lrange);
        add("LINDEX", 3, false, Commands::lindex);

        // Hashes
        add("HSET", -4, true, Commands::hset);
        add("HMSET", -4, true, ctx -> {
            hset(ctx);
            return RespValue.OK;
        });
        add("HGET", 3, false, ctx -> {
            HashValue hash = ctx.typed(ctx.key(1), HashValue.class);
            return RespValue.bulk(hash == null ? null : hash.fields().get(ctx.key(2)));
        });
        add("HMGET", -3, false, Commands::hmget);
        add("HDEL", -3, true, Commands::hdel);
        add("HGETALL", 2, false, Commands::hgetall);
        add("HKEYS", 2, false, ctx -> hashPart(ctx, true));
        add("HVALS", 2, false, ctx -> hashPart(ctx, false));
        add("HLEN", 2, false, ctx -> {
            HashValue hash = ctx.typed(ctx.key(1), HashValue.class);
            return RespValue.integer(hash == null ? 0 : hash.fields().size());
        });
        add("HEXISTS", 3, false, ctx -> {
            HashValue hash = ctx.typed(ctx.key(1), HashValue.class);
            return RespValue.bool(hash != null && hash.fields().containsKey(ctx.key(2)));
        });
        add("HINCRBY", 4, true, Commands::hincrby);

        // Sets
        add("SADD", -3, true, Commands::sadd);
        add("SREM", -3, true, Commands::srem);
        add("SMEMBERS", 2, false, ctx -> {
            SetValue set = ctx.typed(ctx.key(1), SetValue.class);
            return set == null ? RespValue.EMPTY_ARRAY
                    : RespValue.bulkArray(set.members().stream().map(Bytes::bytes).toList());
        });
        add("SISMEMBER", 3, false, ctx -> {
            SetValue set = ctx.typed(ctx.key(1), SetValue.class);
            return RespValue.bool(set != null && set.members().contains(ctx.key(2)));
        });
        add("SCARD", 2, false, ctx -> {
            SetValue set = ctx.typed(ctx.key(1), SetValue.class);
            return RespValue.integer(set == null ? 0 : set.members().size());
        });

        // Server
        add("PING", -1, false, ctx -> ctx.argc() > 1 ? RespValue.bulk(ctx.arg(1)) : RespValue.PONG);
        add("ECHO", 2, false, ctx -> RespValue.bulk(ctx.arg(1)));
        add("PUBLISH", 3, false, ctx -> RespValue.integer(ctx.broker().publish(ctx.key(1), ctx.arg(2))));
        add("INFO", -1, false, ctx -> RespValue.bulk(ctx.broker().info()));
        add("SELECT", 2, false, ctx -> {
            if (ctx.longArg(1) != 0) {
                throw new CommandException("ERR DB index is out of range");
            }
            return RespValue.OK;
        });
        add("TIME", 1, false, ctx -> {
            long micros = ctx.db().now() * 1000;
            return RespValue.array(List.of(
                    RespValue.bulk(Long.toString(micros / 1_000_000)),
                    RespValue.bulk(Long.toString(micros % 1_000_000))));
        });
        // Clients and tools probe these on connect; answering keeps them happy.
        add("COMMAND", -1, false, ctx -> ctx.argc() > 1 && ctx.keyword(1).equals("COUNT")
                ? RespValue.integer(table.size()) : RespValue.EMPTY_ARRAY);
        add("CONFIG", -2, false, Commands::config);
    }

    private void add(String name, int arity, boolean write, CommandSpec.Handler handler) {
        table.put(name, new CommandSpec(name.toLowerCase(Locale.ROOT), arity, write, handler));
    }

    public CommandSpec lookup(String name) {
        return table.get(name.toUpperCase(Locale.ROOT));
    }

    public int size() {
        return table.size();
    }

    /**
     * Runs one command atomically under the database lock. Successful writes are handed to {@code persist}
     * (the append-only file) while the lock is still held, so the file has the same order as memory.
     */
    public RespValue execute(Context ctx, Consumer<List<byte[]>> persist) {
        if (ctx.argc() == 0) {
            return RespValue.error("ERR empty command");
        }
        String name = new String(ctx.arg(0), StandardCharsets.UTF_8);
        CommandSpec spec = lookup(name);
        if (spec == null) {
            return RespValue.error(unknown(name, ctx.args()));
        }
        if (!spec.arityOk(ctx.argc())) {
            return RespValue.error("ERR wrong number of arguments for '" + spec.name() + "' command");
        }
        var lock = ctx.db().lock();
        lock.lock();
        try {
            RespValue reply = spec.handler().handle(ctx);
            if (spec.write() && persist != null && !(reply instanceof RespValue.ErrorReply)) {
                ctx.toPersist().forEach(persist);
            }
            return reply;
        } catch (CommandException e) {
            return RespValue.error(e.getMessage());
        } catch (ArithmeticException e) {
            return RespValue.error("ERR value is out of range");
        } finally {
            lock.unlock();
        }
    }

    private static String unknown(String name, List<byte[]> args) {
        String sample = args.stream().skip(1).limit(3)
                .map(a -> "'" + new String(a, StandardCharsets.UTF_8) + "'")
                .collect(Collectors.joining(" "));
        return "ERR unknown command '" + name + "', with args beginning with: " + sample;
    }

    /** Only the parameters tools ask for on connect (redis-benchmark reads save and appendonly). */
    private static final Map<String, String> CONFIG = Map.of("save", "", "appendonly", "no", "databases", "1");

    private static RespValue config(Context ctx) throws CommandException {
        if (!ctx.keyword(1).equals("GET")) {
            throw new CommandException("ERR CONFIG " + ctx.keyword(1) + " is not supported");
        }
        List<RespValue> out = new ArrayList<>();
        for (int i = 2; i < ctx.argc(); i++) {
            byte[] pattern = ctx.arg(i);
            for (var entry : CONFIG.entrySet()) {
                if (Glob.matches(pattern, entry.getKey().getBytes(StandardCharsets.UTF_8))) {
                    out.add(RespValue.bulk(entry.getKey()));
                    out.add(RespValue.bulk(entry.getValue()));
                }
            }
        }
        return RespValue.array(out);
    }

    // ---- strings ----

    private static RespValue get(Context ctx) throws CommandException {
        StringValue value = ctx.typed(ctx.key(1), StringValue.class);
        return RespValue.bulk(value == null ? null : value.data());
    }

    /** SET key value [NX | XX] [GET] [EX s | PX ms | EXAT s | PXAT ms | KEEPTTL] */
    private static RespValue set(Context ctx) throws CommandException {
        Bytes key = ctx.key(1);
        byte[] data = ctx.arg(2);
        boolean nx = false, xx = false, get = false, keepTtl = false;
        long expiresAt = 0;
        int ttlOptions = 0;
        for (int i = 3; i < ctx.argc(); i++) {
            String option = ctx.keyword(i);
            switch (option) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "GET" -> get = true;
                case "KEEPTTL" -> {
                    keepTtl = true;
                    ttlOptions++;
                }
                case "EX", "PX", "EXAT", "PXAT" -> {
                    if (++i >= ctx.argc()) {
                        throw new CommandException(CommandException.SYNTAX);
                    }
                    long amount = ctx.longArg(i);
                    if (amount <= 0) {
                        throw new CommandException("ERR invalid expire time in 'set' command");
                    }
                    long now = ctx.db().now();
                    expiresAt = switch (option) {
                        case "EX" -> now + Math.multiplyExact(amount, 1000L);
                        case "PX" -> now + amount;
                        case "EXAT" -> Math.multiplyExact(amount, 1000L);
                        default -> amount;
                    };
                    ttlOptions++;
                }
                default -> throw new CommandException(CommandException.SYNTAX);
            }
        }
        if ((nx && xx) || ttlOptions > 1) {
            throw new CommandException(CommandException.SYNTAX);
        }
        Value old = ctx.db().get(key);
        if (get && old != null && !(old instanceof StringValue)) {
            throw new CommandException(CommandException.WRONGTYPE);
        }
        RespValue previous = get ? RespValue.bulk(old instanceof StringValue s ? s.data() : null) : null;
        if ((nx && old != null) || (xx && old == null)) {
            ctx.propagateNothing();
            return get ? previous : RespValue.NIL;
        }
        if (keepTtl) {
            ctx.db().replaceValue(key, new StringValue(data));
            ctx.propagate(ascii("SET"), key.bytes(), data, ascii("KEEPTTL"));
        } else {
            ctx.db().put(key, new StringValue(data));
            if (expiresAt != 0) {
                ctx.db().expireAt(key, expiresAt);
                ctx.propagate(ascii("SET"), key.bytes(), data, ascii("PXAT"), number(expiresAt));
            } else {
                ctx.propagate(ascii("SET"), key.bytes(), data);
            }
        }
        return get ? previous : RespValue.OK;
    }

    private static boolean setIfAbsent(Context ctx, Bytes key, byte[] data) {
        if (ctx.db().exists(key)) {
            ctx.propagateNothing();
            return false;
        }
        ctx.db().put(key, new StringValue(data));
        ctx.propagate(ascii("SET"), key.bytes(), data);
        return true;
    }

    private static RespValue setWithTtl(Context ctx, long unitMillis) throws CommandException {
        long amount = ctx.longArg(2);
        if (amount <= 0) {
            throw new CommandException("ERR invalid expire time in '" + ctx.keyword(0).toLowerCase(Locale.ROOT) + "' command");
        }
        Bytes key = ctx.key(1);
        long expiresAt = ctx.db().now() + Math.multiplyExact(amount, unitMillis);
        ctx.db().put(key, new StringValue(ctx.arg(3)));
        ctx.db().expireAt(key, expiresAt);
        ctx.propagate(ascii("SET"), key.bytes(), ctx.arg(3), ascii("PXAT"), number(expiresAt));
        return RespValue.OK;
    }

    private static RespValue getSet(Context ctx) throws CommandException {
        StringValue old = ctx.typed(ctx.key(1), StringValue.class);
        ctx.db().put(ctx.key(1), new StringValue(ctx.arg(2)));
        ctx.propagate(ascii("SET"), ctx.arg(1), ctx.arg(2));
        return RespValue.bulk(old == null ? null : old.data());
    }

    private static RespValue getDel(Context ctx) throws CommandException {
        StringValue old = ctx.typed(ctx.key(1), StringValue.class);
        if (old == null) {
            ctx.propagateNothing();
            return RespValue.NIL;
        }
        ctx.db().delete(ctx.key(1));
        ctx.propagate(ascii("DEL"), ctx.arg(1));
        return RespValue.bulk(old.data());
    }

    private static RespValue mget(Context ctx) {
        List<RespValue> out = new ArrayList<>(ctx.argc() - 1);
        for (int i = 1; i < ctx.argc(); i++) {
            // MGET never fails on wrong types: those keys are simply nil.
            out.add(RespValue.bulk(ctx.db().get(ctx.key(i)) instanceof StringValue s ? s.data() : null));
        }
        return RespValue.array(out);
    }

    private static RespValue mset(Context ctx) throws CommandException {
        if (ctx.argc() % 2 == 0) {
            throw new CommandException("ERR wrong number of arguments for 'mset' command");
        }
        for (int i = 1; i < ctx.argc(); i += 2) {
            ctx.db().put(ctx.key(i), new StringValue(ctx.arg(i + 1)));
        }
        return RespValue.OK;
    }

    private static RespValue msetnx(Context ctx) throws CommandException {
        if (ctx.argc() % 2 == 0) {
            throw new CommandException("ERR wrong number of arguments for 'msetnx' command");
        }
        for (int i = 1; i < ctx.argc(); i += 2) {
            if (ctx.db().exists(ctx.key(i))) {
                ctx.propagateNothing();
                return RespValue.ZERO;
            }
        }
        mset(ctx);
        return RespValue.ONE;
    }

    private static long negate(long n) throws CommandException {
        if (n == Long.MIN_VALUE) {
            throw new CommandException("ERR decrement would overflow");
        }
        return -n;
    }

    private static RespValue incrBy(Context ctx, long delta) throws CommandException {
        Bytes key = ctx.key(1);
        StringValue current = ctx.typed(key, StringValue.class);
        long value = current == null ? 0 : Context.parseLong(current.data());
        long result;
        try {
            result = Math.addExact(value, delta);
        } catch (ArithmeticException e) {
            throw new CommandException("ERR increment or decrement would overflow");
        }
        ctx.db().replaceValue(key, new StringValue(number(result)));
        return RespValue.integer(result);
    }

    private static RespValue append(Context ctx) throws CommandException {
        Bytes key = ctx.key(1);
        StringValue current = ctx.typed(key, StringValue.class);
        byte[] base = current == null ? new byte[0] : current.data();
        byte[] extra = ctx.arg(2);
        byte[] joined = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, joined, base.length, extra.length);
        ctx.db().replaceValue(key, new StringValue(joined));
        return RespValue.integer(joined.length);
    }

    private static RespValue strlen(Context ctx) throws CommandException {
        StringValue value = ctx.typed(ctx.key(1), StringValue.class);
        return RespValue.integer(value == null ? 0 : value.data().length);
    }

    // ---- keys ----

    private static RespValue del(Context ctx) {
        long removed = 0;
        for (int i = 1; i < ctx.argc(); i++) {
            if (ctx.db().delete(ctx.key(i))) {
                removed++;
            }
        }
        return RespValue.integer(removed);
    }

    private static RespValue exists(Context ctx) {
        long found = 0;
        for (int i = 1; i < ctx.argc(); i++) {
            if (ctx.db().exists(ctx.key(i))) {
                found++;
            }
        }
        return RespValue.integer(found);
    }

    private static RespValue type(Context ctx) {
        Value value = ctx.db().get(ctx.key(1));
        return new RespValue.SimpleString(value == null ? "none" : value.typeName());
    }

    private static RespValue flush(Context ctx) throws CommandException {
        if (ctx.argc() > 2 || (ctx.argc() == 2 && !List.of("SYNC", "ASYNC").contains(ctx.keyword(1)))) {
            throw new CommandException(CommandException.SYNTAX);
        }
        ctx.db().clear();
        return RespValue.OK;
    }

    private static RespValue rename(Context ctx) throws CommandException {
        Bytes from = ctx.key(1), to = ctx.key(2);
        var entry = ctx.db().entry(from);
        if (entry == null) {
            throw new CommandException("ERR no such key");
        }
        long expiresAt = entry.expiresAt();
        Value value = entry.value();
        ctx.db().delete(from);
        ctx.db().put(to, value);
        if (expiresAt != 0) {
            ctx.db().expireAt(to, expiresAt);
        }
        return RespValue.OK;
    }

    private static RespValue expire(Context ctx, long base, long unitMillis) throws CommandException {
        long amount = ctx.longArg(2);
        long at;
        try {
            at = Math.addExact(base, Math.multiplyExact(amount, unitMillis));
        } catch (ArithmeticException e) {
            throw new CommandException("ERR invalid expire time in '" + ctx.keyword(0).toLowerCase(Locale.ROOT) + "' command");
        }
        Bytes key = ctx.key(1);
        boolean found = ctx.db().expireAt(key, at);
        if (!found) {
            ctx.propagateNothing();
        } else {
            ctx.propagate(ascii("PEXPIREAT"), key.bytes(), number(at));
        }
        return RespValue.bool(found);
    }

    private static RespValue ttl(Context ctx, long unitMillis) {
        long millis = ctx.db().ttlMillis(ctx.key(1));
        if (millis < 0) {
            return RespValue.integer(millis);
        }
        // Like Redis, TTL rounds to the nearest second.
        return RespValue.integer(unitMillis == 1 ? millis : (millis + 500) / 1000);
    }

    // ---- lists ----

    private static RespValue push(Context ctx, boolean left) throws CommandException {
        Bytes key = ctx.key(1);
        ListValue list = ctx.typed(key, ListValue.class);
        if (list == null) {
            list = (ListValue) ctx.db().getOrCreate(key, ListValue::new);
        }
        for (int i = 2; i < ctx.argc(); i++) {
            if (left) {
                list.items().addFirst(ctx.arg(i));
            } else {
                list.items().addLast(ctx.arg(i));
            }
        }
        return RespValue.integer(list.items().size());
    }

    private static RespValue pop(Context ctx, boolean left) throws CommandException {
        if (ctx.argc() > 3) {
            throw new CommandException(CommandException.SYNTAX);
        }
        Bytes key = ctx.key(1);
        boolean withCount = ctx.argc() == 3;
        long count = withCount ? ctx.longArg(2) : 1;
        if (count < 0) {
            throw new CommandException("ERR value is out of range, must be positive");
        }
        ListValue list = ctx.typed(key, ListValue.class);
        if (list == null) {
            ctx.propagateNothing();
            return withCount ? RespValue.NIL_ARRAY : RespValue.NIL;
        }
        List<byte[]> popped = new ArrayList<>();
        while (popped.size() < count && !list.items().isEmpty()) {
            popped.add(left ? list.items().pollFirst() : list.items().pollLast());
        }
        ctx.db().deleteIfEmpty(key, list);
        if (!withCount) {
            return RespValue.bulk(popped.getFirst());
        }
        return RespValue.bulkArray(popped);
    }

    private static RespValue lrange(Context ctx) throws CommandException {
        ListValue list = ctx.typed(ctx.key(1), ListValue.class);
        if (list == null) {
            return RespValue.EMPTY_ARRAY;
        }
        int size = list.items().size();
        long start = ctx.longArg(2), stop = ctx.longArg(3);
        if (start < 0) {
            start = Math.max(0, size + start);
        }
        if (stop < 0) {
            stop = size + stop;
        }
        stop = Math.min(stop, size - 1);
        if (start > stop) {
            return RespValue.EMPTY_ARRAY;
        }
        List<byte[]> out = new ArrayList<>((int) (stop - start + 1));
        Iterator<byte[]> it = list.items().iterator();
        for (long i = 0; i <= stop; i++) {
            byte[] item = it.next();
            if (i >= start) {
                out.add(item);
            }
        }
        return RespValue.bulkArray(out);
    }

    private static RespValue lindex(Context ctx) throws CommandException {
        ListValue list = ctx.typed(ctx.key(1), ListValue.class);
        if (list == null) {
            return RespValue.NIL;
        }
        int size = list.items().size();
        long index = ctx.longArg(2);
        if (index < 0) {
            index += size;
        }
        if (index < 0 || index >= size) {
            return RespValue.NIL;
        }
        Iterator<byte[]> it = list.items().iterator();
        for (long i = 0; i < index; i++) {
            it.next();
        }
        return RespValue.bulk(it.next());
    }

    // ---- hashes ----

    private static RespValue hset(Context ctx) throws CommandException {
        if (ctx.argc() % 2 != 0) {
            throw new CommandException("ERR wrong number of arguments for '" + ctx.keyword(0).toLowerCase(Locale.ROOT) + "' command");
        }
        Bytes key = ctx.key(1);
        HashValue hash = ctx.typed(key, HashValue.class);
        if (hash == null) {
            hash = (HashValue) ctx.db().getOrCreate(key, HashValue::new);
        }
        long added = 0;
        for (int i = 2; i < ctx.argc(); i += 2) {
            if (hash.fields().put(ctx.key(i), ctx.arg(i + 1)) == null) {
                added++;
            }
        }
        return RespValue.integer(added);
    }

    private static RespValue hmget(Context ctx) throws CommandException {
        HashValue hash = ctx.typed(ctx.key(1), HashValue.class);
        List<RespValue> out = new ArrayList<>();
        for (int i = 2; i < ctx.argc(); i++) {
            out.add(RespValue.bulk(hash == null ? null : hash.fields().get(ctx.key(i))));
        }
        return RespValue.array(out);
    }

    private static RespValue hdel(Context ctx) throws CommandException {
        Bytes key = ctx.key(1);
        HashValue hash = ctx.typed(key, HashValue.class);
        if (hash == null) {
            return RespValue.ZERO;
        }
        long removed = 0;
        for (int i = 2; i < ctx.argc(); i++) {
            if (hash.fields().remove(ctx.key(i)) != null) {
                removed++;
            }
        }
        ctx.db().deleteIfEmpty(key, hash);
        return RespValue.integer(removed);
    }

    private static RespValue hgetall(Context ctx) throws CommandException {
        HashValue hash = ctx.typed(ctx.key(1), HashValue.class);
        if (hash == null) {
            return RespValue.EMPTY_ARRAY;
        }
        List<RespValue> out = new ArrayList<>(hash.fields().size() * 2);
        hash.fields().forEach((field, value) -> {
            out.add(RespValue.bulk(field.bytes()));
            out.add(RespValue.bulk(value));
        });
        return RespValue.array(out);
    }

    private static RespValue hashPart(Context ctx, boolean keys) throws CommandException {
        HashValue hash = ctx.typed(ctx.key(1), HashValue.class);
        if (hash == null) {
            return RespValue.EMPTY_ARRAY;
        }
        return RespValue.bulkArray(keys
                ? hash.fields().keySet().stream().map(Bytes::bytes).toList()
                : List.copyOf(hash.fields().values()));
    }

    private static RespValue hincrby(Context ctx) throws CommandException {
        Bytes key = ctx.key(1);
        long delta = ctx.longArg(3);
        HashValue hash = ctx.typed(key, HashValue.class);
        if (hash == null) {
            hash = (HashValue) ctx.db().getOrCreate(key, HashValue::new);
        }
        byte[] current = hash.fields().get(ctx.key(2));
        long value;
        try {
            value = current == null ? 0 : Context.parseLong(current);
        } catch (CommandException e) {
            throw new CommandException("ERR hash value is not an integer");
        }
        long result;
        try {
            result = Math.addExact(value, delta);
        } catch (ArithmeticException e) {
            throw new CommandException("ERR increment or decrement would overflow");
        }
        hash.fields().put(ctx.key(2), number(result));
        return RespValue.integer(result);
    }

    // ---- sets ----

    private static RespValue sadd(Context ctx) throws CommandException {
        Bytes key = ctx.key(1);
        SetValue set = ctx.typed(key, SetValue.class);
        if (set == null) {
            set = (SetValue) ctx.db().getOrCreate(key, SetValue::new);
        }
        long added = 0;
        for (int i = 2; i < ctx.argc(); i++) {
            if (set.members().add(ctx.key(i))) {
                added++;
            }
        }
        return RespValue.integer(added);
    }

    private static RespValue srem(Context ctx) throws CommandException {
        Bytes key = ctx.key(1);
        SetValue set = ctx.typed(key, SetValue.class);
        if (set == null) {
            return RespValue.ZERO;
        }
        long removed = 0;
        for (int i = 2; i < ctx.argc(); i++) {
            if (set.members().remove(ctx.key(i))) {
                removed++;
            }
        }
        ctx.db().deleteIfEmpty(key, set);
        return RespValue.integer(removed);
    }
}
