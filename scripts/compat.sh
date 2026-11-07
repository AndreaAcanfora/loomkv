#!/usr/bin/env bash
# Talks to a running loomkv with the real redis-cli and checks the answers.
# Usage: scripts/compat.sh [port]
set -euo pipefail
PORT="${1:-6380}"
cli() { redis-cli -p "$PORT" "$@"; }
failures=0
check() {
  local expected="$1"; shift
  local actual
  actual="$(cli "$@" 2>&1)"
  if [[ "$actual" == "$expected" ]]; then
    echo "ok    $*"
  else
    echo "FAIL  $*"; echo "      expected: $expected"; echo "      got:      $actual"
    failures=$((failures + 1))
  fi
}

cli flushall >/dev/null
check PONG          ping
check OK            set greeting ciao
check ciao          get greeting
check 4             strlen greeting
check OK            set session abc EX 100
check 100           ttl session
check 1             persist session
check -1            ttl session
check 1             incr visits
check 11            incrby visits 10
check 3             rpush queue a b c
check $'a\nb\nc'    lrange queue 0 -1
check a             lpop queue
check 2             hset user:1 name Ada lang it
check Ada           hget user:1 name
check 2             sadd tags java redis
check 1             sismember tags java
check list          type queue
check 1             exists greeting
check 1             del greeting
check ""            get greeting
check "WRONGTYPE Operation against a key holding the wrong kind of value" get queue
check 0             publish news hello
check OK            mset a 1 b 2
check $'1\n2'       mget a b

if (( failures > 0 )); then
  echo "$failures compatibility checks failed"; exit 1
fi
echo "all redis-cli checks passed ($(redis-cli --version))"
