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

`redis-benchmark` against loomkv and against **Redis 7.0.15 on the same machine** (a 4-vCPU GitHub Actions runner,
Java 25.0.5, 50 clients, 200,000 requests per test). The [CI job](.github/workflows/ci.yml) reruns it on every
change; these are the numbers from the first run, in requests per second:

| Test | loomkv | Redis | | loomkv, pipeline 16 | Redis, pipeline 16 | |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| SET | 45,714 | 45,851 | 100% | 543,478 | 327,869 | 166% |
| GET | 45,704 | 47,755 | 96% | 675,676 | 477,327 | 142% |
| INCR | 47,148 | 47,159 | 100% | 542,005 | 573,066 | 95% |
| LPUSH | 46,631 | 50,025 | 93% | 602,410 | 373,832 | 161% |
| RPOP | 42,900 | 50,684 | 85% | 483,092 | 371,747 | 130% |
| HSET | 47,676 | 43,039 | 111% | 557,103 | 302,572 | 184% |
| SADD | 46,051 | 48,008 | 96% | 576,369 | 372,439 | 155% |
| LRANGE (first 100) | 30,516 | 38,425 | 79% | 102,775 | 122,100 | 84% |
| MSET (10 keys) | 46,992 | 54,392 | 86% | 366,300 | 415,800 | 88% |

How to read this, honestly:

- **Without pipelining both are bound by network round trips**, and loomkv is within about 15% of Redis.
- **With pipelining loomkv often comes out ahead.** That is not because Java beats C: Redis 7 runs commands and,
  by default, network I/O on a single core, while loomkv parses requests and writes replies on every core (one
  virtual thread per client) and serializes only the in-memory work. Where that work is heavier, like building a
  100-element `LRANGE` reply or ten `MSET` writes, Redis is faster.
- It is one run on a shared CI machine, so expect noise of 10-20%. And Redis does far more: replication,
  persistence options, memory management, dozens of data types. loomkv is a learning project, not a replacement.

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
