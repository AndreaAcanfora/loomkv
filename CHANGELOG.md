# Changelog

## 0.1.0 (2026-11-07)

First version.

- RESP2 server with one virtual thread per client, pipelining and inline commands.
- Strings (SET with NX/XX/GET/EX/PX/EXAT/PXAT/KEEPTTL, counters, MGET/MSET...), keys and expiry, lists, hashes,
  sets, pub/sub, INFO, CLIENT, CONFIG GET.
- Lazy and active expiry (bounded per tick).
- Append-only file with absolute times, fsync policies, and recovery from a torn last write.
- Error messages matching Redis; tested with Jedis, redis-cli and redis-benchmark.
