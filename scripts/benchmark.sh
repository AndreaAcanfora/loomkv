#!/usr/bin/env bash
# Runs redis-benchmark against loomkv and against a real Redis on the same machine, and prints a
# Markdown table. Usage: scripts/benchmark.sh <loomkv port> <redis port>
set -euo pipefail
LOOMKV="${1:-6380}"
REDIS="${2:-6379}"
TESTS="ping,set,get,incr,lpush,rpop,sadd,hset,lrange_100,mset"
REQUESTS="${REQUESTS:-200000}"
CLIENTS="${CLIENTS:-50}"

run() { redis-benchmark -p "$1" -t "$TESTS" -n "$REQUESTS" -c "$CLIENTS" -P "$2" --csv 2>/dev/null | tail -n +2 | tr -d '"' | cut -d, -f1,2; }

for pipeline in 1 16; do
  echo
  echo "#### $CLIENTS clients, pipeline $pipeline ($REQUESTS requests per test)"
  echo
  echo "| Test | loomkv (req/s) | Redis (req/s) | loomkv / Redis |"
  echo "| --- | ---: | ---: | ---: |"
  join -t, <(run "$LOOMKV" "$pipeline" | sort) <(run "$REDIS" "$pipeline" | sort) |
    awk -F, '{ printf "| %s | %\047.0f | %\047.0f | %.0f%% |\n", $1, $2, $3, 100 * $2 / $3 }'
done
