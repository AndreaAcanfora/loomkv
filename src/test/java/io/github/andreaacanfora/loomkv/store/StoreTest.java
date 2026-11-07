package io.github.andreaacanfora.loomkv.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andreaacanfora.loomkv.store.Value.StringValue;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreTest {

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        long millis = 1_000_000;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private static boolean glob(String pattern, String text) {
        return Glob.matches(pattern.getBytes(StandardCharsets.UTF_8), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void globMatchesLikeRedis() {
        assertTrue(glob("*", "anything"));
        assertTrue(glob("user:*", "user:42"));
        assertFalse(glob("user:*", "session:42"));
        assertTrue(glob("h?llo", "hello"));
        assertFalse(glob("h?llo", "hllo"));
        assertTrue(glob("h[ae]llo", "hallo"));
        assertFalse(glob("h[^e]llo", "hello"));
        assertTrue(glob("h[a-c]llo", "hbllo"));
        assertTrue(glob("a\\*b", "a*b"));
        assertFalse(glob("a\\*b", "axb"));
        assertTrue(glob("*:*:end", "a:b:c:end"));
        assertTrue(glob("", ""));
    }

    @Test
    void keysExpireLazilyAndActively() {
        ManualClock clock = new ManualClock();
        Database db = new Database(clock);
        Bytes lazy = Bytes.of("lazy"), active = Bytes.of("active"), forever = Bytes.of("forever");
        db.put(lazy, new StringValue(new byte[] {1}));
        db.put(active, new StringValue(new byte[] {2}));
        db.put(forever, new StringValue(new byte[] {3}));
        assertTrue(db.expireAt(lazy, clock.millis + 1000));
        assertTrue(db.expireAt(active, clock.millis + 1000));
        assertEquals(1000, db.ttlMillis(lazy));
        assertEquals(-1, db.ttlMillis(forever));
        assertEquals(-2, db.ttlMillis(Bytes.of("missing")));

        clock.millis += 1000;
        assertEquals(1, db.expireDue(1)); // budget of one: only one key removed now
        assertEquals(2, db.size());
        assertEquals(1, db.expireDue(100));
        assertEquals(1, db.size());
        assertNull(db.get(lazy));
        assertNull(db.get(active));
        assertEquals(2, db.expiredKeys());
    }

    @Test
    void changedOrRemovedTtlsAreNotExpiredByStaleDeadlines() {
        ManualClock clock = new ManualClock();
        Database db = new Database(clock);
        Bytes key = Bytes.of("k");
        db.put(key, new StringValue(new byte[0]));
        db.expireAt(key, clock.millis + 100);
        db.expireAt(key, clock.millis + 10_000); // extended
        clock.millis += 200;
        assertEquals(0, db.expireDue(100));
        assertTrue(db.exists(key));
        assertTrue(db.persist(key));
        clock.millis += 20_000;
        assertEquals(0, db.expireDue(100));
        assertTrue(db.exists(key));
        // Setting a new value clears the TTL; replaceValue keeps it.
        db.expireAt(key, clock.millis + 50);
        db.replaceValue(key, new StringValue(new byte[] {9}));
        assertEquals(50, db.ttlMillis(key));
        db.put(key, new StringValue(new byte[] {9}));
        assertEquals(-1, db.ttlMillis(key));
    }

    @Test
    void expiringInThePastDeletes() {
        ManualClock clock = new ManualClock();
        Database db = new Database(clock);
        Bytes key = Bytes.of("k");
        db.put(key, new StringValue(new byte[0]));
        assertTrue(db.expireAt(key, clock.millis - 1));
        assertFalse(db.exists(key));
        assertFalse(db.expireAt(key, clock.millis + 1));
    }

    @Test
    void keysSkipsExpiredEntries() {
        ManualClock clock = new ManualClock();
        Database db = new Database(clock);
        for (String name : List.of("user:1", "user:2", "order:1")) {
            db.put(Bytes.of(name), new StringValue(new byte[0]));
        }
        db.expireAt(Bytes.of("user:2"), clock.millis + 10);
        clock.millis += 10;
        List<Bytes> keys = db.keys("user:*".getBytes(StandardCharsets.UTF_8));
        keys.sort(Comparator.naturalOrder());
        assertEquals(List.of(Bytes.of("user:1")), keys);
    }
}
