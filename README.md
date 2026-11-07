<div align="center">

# loomkv

**A Redis-compatible key-value server in modern Java: one virtual thread per client, zero dependencies.**

Point `redis-cli`, Jedis, redis-py or any Redis client at it and it just works: strings with expiry, lists, hashes,
sets, pub/sub, pipelining, append-only persistence. About 2,000 lines of plain Java 25, no frameworks, a 70 KB jar.

[![CI](https://github.com/AndreaAcanfora/loomkv/actions/workflows/ci.yml/badge.svg)](https://github.com/AndreaAcanfora/loomkv/actions/workflows/ci.yml)
[![Java 25](https://img.shields.io/badge/java-25-ED8B00?logo=openjdk&logoColor=white)](pom.xml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Donate with PayPal](https://img.shields.io/badge/donate-PayPal-00457C?logo=paypal)](https://www.paypal.com/paypalme/AndreaAca)

</div>

---

```console
$ java -jar loomkv.jar --aof data.aof
loomkv 0.1.0 on 127.0.0.1:6379 (Java 25, virtual threads), AOF data.aof: 0 commands replayed

$ redis-cli
127.0.0.1:6379> SET session:42 "ada" EX 3600
OK
127.0.0.1:6379> TTL session:42
(integer) 3600
127.0.0.1:6379> RPUSH queue job1 job2 job3
(integer) 3
127.0.0.1:6379> HSET user:1 name Ada lang it
(integer) 2
127.0.0.1:6379> GET queue
(error) WRONGTYPE Operation against a key holding the wrong kind of value
```

## Why

It's a learning and showcase project: how far can **modern Java** go with **simple, blocking code**?

- **One virtual thread per connection.** No event loop, no callbacks, no reactive streams: each client is a plain
  `while` loop that reads a command and writes a reply. Virtual threads (Project Loom, Java 21+) make that scale to
  tens of thousands of connections.
- **Data-oriented Java.** Replies and values are `sealed` interfaces of `record`s, and the compiler checks every
  `switch` over them handles every case.
- **Wire-compatible.** The RESP2 protocol is implemented by hand, error messages match Redis word for word, and the
  test suite runs against [Jedis](https://github.com/redis/jedis), the official Java client for Redis. CI also
  checks it with the real `redis-cli` and benchmarks it with `redis-benchmark` next to a real Redis.

## Quick start

```bash
git clone https://github.com/AndreaAcanfora/loomkv && cd loomkv
mvn -q package                     # needs JDK 25; runs the tests too
java -jar target/loomkv.jar        # then: redis-cli ping
```

Or with Docker:

```bash
docker run -d -p 6379:6379 -v loomkv-data:/data ghcr.io/andreaacanfora/loomkv:latest
```

| Option | Default | |
| --- | --- | --- |
| `--port <n>` | `6379` | |
| `--bind <address>` | `127.0.0.1` | `0.0.0.0` to accept remote clients (there is no AUTH yet) |
| `--aof <file>` | memory only | log writes to an append-only file and replay it on start |
| `--appendfsync <policy>` | `everysec` | `always` (safest), `everysec`, or `no` (let the OS decide) |

## Commands

| Group | Commands |
| --- | --- |
| Strings | `GET` `SET` (with `NX` `XX` `GET` `EX` `PX` `EXAT` `PXAT` `KEEPTTL`) `SETNX` `SETEX` `PSETEX` `GETSET` `GETDEL` `MGET` `MSET` `MSETNX` `INCR` `INCRBY` `DECR` `DECRBY` `APPEND` `STRLEN` |
| Keys | `DEL` `UNLINK` `EXISTS` `TYPE` `KEYS` (glob patterns) `RENAME` `DBSIZE` `FLUSHDB` `FLUSHALL` |
| Expiry | `EXPIRE` `PEXPIRE` `EXPIREAT` `PEXPIREAT` `TTL` `PTTL` `PERSIST` |
| Lists | `LPUSH` `RPUSH` `LPOP` `RPOP` (with count) `LLEN` `LRANGE` `LINDEX` |
| Hashes | `HSET` `HMSET` `HGET` `HMGET` `HDEL` `HGETALL` `HKEYS` `HVALS` `HLEN` `HEXISTS` `HINCRBY` |
| Sets | `SADD` `SREM` `SMEMBERS` `SISMEMBER` `SCARD` |
| Pub/sub | `SUBSCRIBE` `UNSUBSCRIBE` `PUBLISH` |
| Server | `PING` `ECHO` `INFO` `TIME` `SELECT 0` `CLIENT SETNAME/GETNAME/ID/LIST/SETINFO` `CONFIG GET` `COMMAND` `QUIT` |

Keys and values are binary-safe byte strings, like in Redis.

## Benchmarks

BENCHMARKS_PLACEHOLDER

## How it works

```text
             ┌────────────── one virtual thread per client ──────────────┐
socket ──▶ RespReader ──▶ Commands.execute ──▶ RespWriter ──▶ socket
                               │   (holds the database lock: each command is atomic)
                               ├──▶ Database: HashMap<Bytes, Entry>, TTLs, expiry queue
                               └──▶ AppendOnlyFile (write commands, in memory order)
```

- **Atomicity like Redis.** Redis runs one command at a time. loomkv does the same with a single lock held for the
  in-memory part of each command (microseconds), so `INCR`, `LPUSH a b c` or `MSET` are atomic, while reading,
  parsing and writing sockets happen in parallel on each client's virtual thread. The test suite checks it with 200
  concurrent clients doing 50,000 `INCR`s: the total is always exact.
- **Pipelining.** Replies are buffered and flushed only when the client has no more requests waiting, so 10,000
  pipelined commands go back in a handful of writes.
- **Expiry, lazy and active.** An expired key is deleted when someone touches it, and a background virtual thread
  removes due keys ten times a second from a priority queue, a bounded amount per tick so it never stalls the server.
- **Persistence you can trust.** The AOF stores write commands in RESP. Relative times are rewritten as absolute
  ones (`EXPIRE k 10` is logged as `PEXPIREAT k <timestamp>`), so a replay a day later gives the same result. If the
  process died in the middle of a write, the incomplete tail is cut off on startup instead of corrupting the file.
- **Slow subscribers can't stall publishers.** `PUBLISH` only enqueues; each subscriber writes on its own virtual
  thread and is disconnected if it falls 10,000 messages behind (Redis does the same with its output buffer limit).

```text
src/main/java/io/github/andreaacanfora/loomkv/
  resp/      RESP2 reader (arrays and inline commands) and writer; RespValue is a sealed interface of records
  store/     Database (keyspace, TTLs, expiry), Value (sealed: string, list, hash, set), Glob, Bytes
  command/   Commands: the command table, option parsing, AOF rewriting of relative times
  server/    Server (accept loop, INFO), Connection (one per client, pub/sub), AppendOnlyFile
```

## Tests

```bash
mvn verify    # 30 tests, about 15 seconds
```

Unit tests cover the protocol, glob matching, expiry with a manual clock and every command's replies (including the
exact AOF output). Integration tests start a real server and use **Jedis** over sockets: data types, expiry,
10,000 pipelined commands, 200 concurrent clients, pub/sub, restart from the AOF and recovery from a torn write.
CI runs them on Linux, macOS and Windows, then checks the server with `redis-cli` and `redis-benchmark`.

## Not (yet) supported

Sorted sets, streams, transactions (`MULTI`/`EXEC`), Lua scripting, RESP3, `AUTH`, replication, cluster mode, AOF
rewriting/compaction and memory limits with eviction. Some of these are on the roadmap:

- [ ] Sorted sets (`ZADD`, `ZRANGE`, `ZRANK`...) with a skip list
- [ ] `MULTI` / `EXEC` / `WATCH`
- [ ] AOF rewrite in the background
- [ ] `AUTH` and a `requirepass` option
- [ ] GraalVM native image

## Support

loomkv is free and open source. If you learned something from it, you can
[buy me a coffee on PayPal](https://www.paypal.com/paypalme/AndreaAca). Thank you!

## License

[MIT](LICENSE) © Andrea Acanfora. Redis is a registered trademark of Redis Ltd.; loomkv is an independent project
that speaks the same protocol and is not affiliated with Redis Ltd.
