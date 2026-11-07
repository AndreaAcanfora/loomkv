package io.github.andreaacanfora.loomkv.store;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The keyspace. Like Redis, commands run one at a time: every command holds {@link #lock()} for its whole
 * execution, which makes each one atomic (INCR, LPUSH with many values, MSET...). Connections still read,
 * parse and write in parallel on their own virtual threads; only the in-memory work is serialized, and it
 * takes microseconds.
 */
public final class Database {

    private final Map<Bytes, Entry> entries = new HashMap<>();
    /** Keys with a deadline, soonest first. Stale items (TTL changed or key gone) are skipped. */
    private final PriorityQueue<Deadline> deadlines = new PriorityQueue<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;
    private long expiredKeys;

    public Database(Clock clock) {
        this.clock = clock;
    }

    public Database() {
        this(Clock.systemUTC());
    }

    /** A stored value and its absolute expiry in epoch milliseconds (0 = never). */
    public static final class Entry {
        private Value value;
        private long expiresAt;

        Entry(Value value) {
            this.value = value;
        }

        public Value value() {
            return value;
        }

        public long expiresAt() {
            return expiresAt;
        }
    }

    private record Deadline(long at, Bytes key) implements Comparable<Deadline> {
        @Override
        public int compareTo(Deadline other) {
            return Long.compare(at, other.at);
        }
    }

    public ReentrantLock lock() {
        return lock;
    }

    /** Runs work under the database lock. */
    public <T> T locked(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    public long now() {
        return clock.millis();
    }

    /** The live entry for a key, deleting it first if it has expired. Caller holds the lock. */
    public Entry entry(Bytes key) {
        Entry entry = entries.get(key);
        if (entry != null && entry.expiresAt != 0 && entry.expiresAt <= now()) {
            entries.remove(key);
            expiredKeys++;
            return null;
        }
        return entry;
    }

    public Value get(Bytes key) {
        Entry entry = entry(key);
        return entry == null ? null : entry.value;
    }

    /** Stores a value, clearing any TTL (SET semantics). */
    public void put(Bytes key, Value value) {
        entries.put(key, new Entry(value));
    }

    /** Stores a value and keeps the existing TTL if the key is live (KEEPTTL and in-place updates). */
    public void replaceValue(Bytes key, Value value) {
        Entry entry = entry(key);
        if (entry == null) {
            put(key, value);
        } else {
            entry.value = value;
        }
    }

    /** Returns the value, creating it with the factory if the key is missing. */
    public Value getOrCreate(Bytes key, Supplier<Value> factory) {
        Entry entry = entry(key);
        if (entry != null) {
            return entry.value;
        }
        Value value = factory.get();
        entries.put(key, new Entry(value));
        return value;
    }

    public boolean delete(Bytes key) {
        return entry(key) != null && entries.remove(key) != null;
    }

    /** Deletes a collection that became empty. */
    public void deleteIfEmpty(Bytes key, Value value) {
        if (value.isEmpty()) {
            entries.remove(key);
        }
    }

    public boolean exists(Bytes key) {
        return entry(key) != null;
    }

    /** Sets an absolute expiry; a deadline in the past deletes the key. Returns false if the key is missing. */
    public boolean expireAt(Bytes key, long epochMillis) {
        Entry entry = entry(key);
        if (entry == null) {
            return false;
        }
        if (epochMillis <= now()) {
            entries.remove(key);
            return true;
        }
        entry.expiresAt = epochMillis;
        deadlines.add(new Deadline(epochMillis, key));
        return true;
    }

    public boolean persist(Bytes key) {
        Entry entry = entry(key);
        if (entry == null || entry.expiresAt == 0) {
            return false;
        }
        entry.expiresAt = 0;
        return true;
    }

    /** Remaining TTL in ms: -2 if the key is missing, -1 if it has no expiry. */
    public long ttlMillis(Bytes key) {
        Entry entry = entry(key);
        if (entry == null) {
            return -2;
        }
        return entry.expiresAt == 0 ? -1 : Math.max(0, entry.expiresAt - now());
    }

    public List<Bytes> keys(byte[] pattern) {
        long now = now();
        List<Bytes> out = new ArrayList<>();
        for (var e : entries.entrySet()) {
            Entry entry = e.getValue();
            boolean live = entry.expiresAt == 0 || entry.expiresAt > now;
            if (live && Glob.matches(pattern, e.getKey().bytes())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        deadlines.clear();
    }

    public long expiredKeys() {
        return expiredKeys;
    }

    /**
     * Active expiry: deletes keys whose deadline has passed, at most {@code budget} per call, so a burst
     * of expirations never blocks the server for long. Returns how many were deleted.
     */
    public int expireDue(int budget) {
        lock.lock();
        try {
            long now = now();
            int removed = 0;
            while (removed < budget && !deadlines.isEmpty() && deadlines.peek().at() <= now) {
                Deadline due = deadlines.poll();
                Entry entry = entries.get(due.key());
                if (entry != null && entry.expiresAt == due.at()) {
                    entries.remove(due.key());
                    expiredKeys++;
                    removed++;
                }
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }
}
