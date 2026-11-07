package io.github.andreaacanfora.loomkv.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andreaacanfora.loomkv.resp.RespValue;
import io.github.andreaacanfora.loomkv.resp.RespWriter;
import io.github.andreaacanfora.loomkv.store.Bytes;
import io.github.andreaacanfora.loomkv.store.Database;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandsTest {

    static final long NOW = 1_800_000_000_000L;

    private final Database db = new Database(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    private final Commands commands = new Commands();
    private final List<String> persisted = new ArrayList<>();
    private final Broker broker = new Broker() {
        @Override
        public int publish(Bytes channel, byte[] message) {
            return 7;
        }

        @Override
        public String info() {
            return "# Server";
        }
    };

    /** Runs a command and returns its reply in RESP text, which makes assertions easy to read. */
    private String run(String... args) {
        List<byte[]> argv = Arrays.stream(args).map(a -> a.getBytes(StandardCharsets.UTF_8)).toList();
        RespValue reply = commands.execute(new Context(db, broker, argv), cmd -> persisted.add(String.join(" ",
                cmd.stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList())));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new RespWriter(out).write(reply);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "|");
    }

    @Test
    void setOptions() {
        assertEquals("+OK|", run("SET", "k", "v1"));
        assertEquals("$-1|", run("SET", "k", "v2", "NX"));
        assertEquals("$2|v1|", run("SET", "k", "v2", "XX", "GET"));
        assertEquals("$-1|", run("SET", "missing", "v", "XX"));
        assertEquals("+OK|", run("SET", "t", "v", "EX", "10"));
        assertEquals(":10|", run("TTL", "t"));
        assertEquals(":10000|", run("PTTL", "t"));
        assertEquals("+OK|", run("SET", "t", "v2", "KEEPTTL"));
        assertEquals(":10|", run("TTL", "t"));
        assertEquals("+OK|", run("SET", "t", "v3"));
        assertEquals(":-1|", run("TTL", "t"));
        assertEquals("-ERR syntax error|", run("SET", "k", "v", "NX", "XX"));
        assertEquals("-ERR syntax error|", run("SET", "k", "v", "EX", "1", "PX", "5"));
        assertEquals("-ERR syntax error|", run("SET", "k", "v", "EX"));
        assertEquals("-ERR invalid expire time in 'set' command|", run("SET", "k", "v", "EX", "0"));
        assertEquals("-ERR value is out of range|", run("SET", "k", "v", "EX", "9223372036854775807"));
    }

    @Test
    void relativeTimesArePersistedAsAbsolute() {
        run("SET", "a", "1", "EX", "10");
        run("SETEX", "b", "5", "2");
        run("EXPIRE", "a", "100");
        run("EXPIRE", "missing", "100");
        run("SET", "c", "3", "NX");
        run("SET", "c", "4", "NX"); // did nothing: not persisted
        run("INCR", "n");
        run("GET", "a"); // reads are never persisted
        assertEquals(List.of(
                "SET a 1 PXAT " + (NOW + 10_000),
                "SET b 2 PXAT " + (NOW + 5_000),
                "PEXPIREAT a " + (NOW + 100_000),
                "SET c 3",
                "INCR n"), persisted);
    }

    @Test
    void counters() {
        assertEquals(":1|", run("INCR", "n"));
        assertEquals(":11|", run("INCRBY", "n", "10"));
        assertEquals(":8|", run("DECRBY", "n", "3"));
        assertEquals(":7|", run("DECR", "n"));
        run("SET", "s", "abc");
        assertEquals("-ERR value is not an integer or out of range|", run("INCR", "s"));
        run("SET", "max", "9223372036854775807");
        assertEquals("-ERR increment or decrement would overflow|", run("INCR", "max"));
        assertEquals(":6|", run("APPEND", "s", "def"));
        assertEquals(":6|", run("STRLEN", "s"));
    }

    @Test
    void multiKeyStrings() {
        assertEquals("+OK|", run("MSET", "a", "1", "b", "2"));
        run("RPUSH", "list", "x");
        assertEquals("*3|$1|1|$1|2|$-1|", run("MGET", "a", "b", "list"));
        assertEquals(":0|", run("MSETNX", "a", "9", "z", "9"));
        assertEquals("$-1|", run("GET", "z"));
        assertEquals("-ERR wrong number of arguments for 'mset' command|", run("MSET", "a", "1", "b"));
        assertEquals("$1|1|", run("GETDEL", "a"));
        assertEquals(":0|", run("EXISTS", "a"));
    }

    @Test
    void wrongTypeAndArity() {
        run("RPUSH", "list", "x");
        String wrongType = "-WRONGTYPE Operation against a key holding the wrong kind of value|";
        assertEquals(wrongType, run("GET", "list"));
        assertEquals(wrongType, run("HSET", "list", "f", "v"));
        assertEquals(wrongType, run("SADD", "list", "m"));
        assertEquals("-ERR wrong number of arguments for 'get' command|", run("GET"));
        assertEquals("-ERR unknown command 'FLY', with args beginning with: 'to' 'the' 'moon'|",
                run("FLY", "to", "the", "moon", "now"));
    }

    @Test
    void lists() {
        assertEquals(":3|", run("RPUSH", "l", "a", "b", "c"));
        assertEquals(":5|", run("LPUSH", "l", "y", "z"));
        assertEquals("*5|$1|z|$1|y|$1|a|$1|b|$1|c|", run("LRANGE", "l", "0", "-1"));
        assertEquals("*2|$1|b|$1|c|", run("LRANGE", "l", "-2", "100"));
        assertEquals("*0|", run("LRANGE", "l", "4", "2"));
        assertEquals("$1|a|", run("LINDEX", "l", "2"));
        assertEquals("$1|c|", run("LINDEX", "l", "-1"));
        assertEquals("$-1|", run("LINDEX", "l", "99"));
        assertEquals("$1|z|", run("LPOP", "l"));
        assertEquals("*2|$1|c|$1|b|", run("RPOP", "l", "2"));
        assertEquals(":2|", run("LLEN", "l"));
        run("RPOP", "l", "10");
        assertEquals(":0|", run("EXISTS", "l")); // empty lists disappear
        assertEquals("*-1|", run("LPOP", "l", "1"));
        assertEquals("$-1|", run("LPOP", "l"));
    }

    @Test
    void hashes() {
        assertEquals(":2|", run("HSET", "h", "name", "Ada", "year", "1815"));
        assertEquals(":0|", run("HSET", "h", "name", "Ada Lovelace"));
        assertEquals("$12|Ada Lovelace|", run("HGET", "h", "name"));
        assertEquals("*2|$4|1815|$-1|", run("HMGET", "h", "year", "nope"));
        assertEquals("*4|$4|name|$12|Ada Lovelace|$4|year|$4|1815|", run("HGETALL", "h"));
        assertEquals(":1825|", run("HINCRBY", "h", "year", "10"));
        assertEquals("-ERR hash value is not an integer|", run("HINCRBY", "h", "name", "1"));
        assertEquals(":1|", run("HEXISTS", "h", "name"));
        assertEquals("*2|$4|name|$4|year|", run("HKEYS", "h"));
        assertEquals(":2|", run("HDEL", "h", "name", "year", "nope"));
        assertEquals("+none|", run("TYPE", "h")); // the hash is gone once empty
    }

    @Test
    void sets() {
        assertEquals(":2|", run("SADD", "s", "x", "y", "x"));
        assertEquals(":1|", run("SISMEMBER", "s", "x"));
        assertEquals(":2|", run("SCARD", "s"));
        assertEquals("*2|$1|x|$1|y|", run("SMEMBERS", "s"));
        assertEquals(":1|", run("SREM", "s", "x", "nope"));
        assertEquals("+set|", run("TYPE", "s"));
    }

    @Test
    void keysAndServer() {
        run("MSET", "user:1", "a", "user:2", "b", "order:1", "c");
        assertEquals(":2|", run("DEL", "user:1", "user:2", "missing"));
        assertEquals("*1|$7|order:1|", run("KEYS", "order:*"));
        assertEquals(":1|", run("DBSIZE"));
        assertEquals("+OK|", run("RENAME", "order:1", "order:2"));
        assertEquals("-ERR no such key|", run("RENAME", "nope", "x"));
        assertEquals("+PONG|", run("PING"));
        assertEquals("$2|hi|", run("ECHO", "hi"));
        assertEquals(":7|", run("PUBLISH", "news", "hello"));
        assertEquals("-ERR DB index is out of range|", run("SELECT", "1"));
        assertEquals("*2|$4|save|$0||", run("CONFIG", "GET", "save"));
        assertEquals("*0|", run("CONFIG", "GET", "maxmemory"));
        assertEquals("+OK|", run("FLUSHALL"));
        assertEquals(":0|", run("DBSIZE"));
        assertTrue(run("COMMAND", "COUNT").startsWith(":"));
    }
}
