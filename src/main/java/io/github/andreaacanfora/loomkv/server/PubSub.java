package io.github.andreaacanfora.loomkv.server;

import io.github.andreaacanfora.loomkv.resp.RespValue;
import io.github.andreaacanfora.loomkv.store.Bytes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Channel subscriptions. Publishing only enqueues: each subscriber writes on its own virtual thread. */
final class PubSub {

    private final Map<Bytes, Set<Connection>> channels = new ConcurrentHashMap<>();

    void subscribe(Connection connection, Bytes channel) {
        channels.computeIfAbsent(channel, _ -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    void unsubscribe(Connection connection, Bytes channel) {
        channels.computeIfPresent(channel, (_, subscribers) -> {
            subscribers.remove(connection);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    int publish(Bytes channel, byte[] message) {
        Set<Connection> subscribers = channels.get(channel);
        if (subscribers == null) {
            return 0;
        }
        RespValue push = RespValue.array(List.of(
                RespValue.bulk("message"), RespValue.bulk(channel.bytes()), RespValue.bulk(message)));
        int delivered = 0;
        for (Connection subscriber : subscribers) {
            if (subscriber.push(push)) {
                delivered++;
            }
        }
        return delivered;
    }

    int channelCount() {
        return channels.size();
    }
}
