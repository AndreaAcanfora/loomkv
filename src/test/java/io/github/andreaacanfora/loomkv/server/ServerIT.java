package io.github.andreaacanfora.loomkv.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

/** End-to-end tests with Jedis, the official Java client for Redis, over real sockets. */
class ServerIT {

    @TempDir Path dir;
    private Server server;

    @BeforeEach
    void start() throws Exception {
        server = new Server(new Server.Options("127.0.0.1", 0, null, AppendOnlyFile.Fsync.EVERYSEC)).start();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private Jedis client() {
        return new Jedis("127.0.0.1", server.port());
    }

    @Test
    void stringsListsHashesSetsThroughJedis() {
        try (Jedis jedis = client()) {
            assertEquals("PONG", jedis.ping());
            assertEquals("OK", jedis.set("greeting", "ciao", SetParams.setParams().ex(100)));
            assertEquals("ciao", jedis.get("greeting"));
            assertTrue(jedis.ttl("greeting") > 98);
            assertEquals(42, jedis.incrBy("counter", 42));
            assertEquals(3, jedis.rpush("queue", "a", "b", "c"));
            assertEquals(List.of("a", "b", "c"), jedis.lrange("queue", 0, -1));
            assertEquals("a", jedis.lpop("queue"));
            jedis.hset("user:1", Map.of("name", "Ada", "lang", "it"));
            assertEquals(Map.of("name", "Ada", "lang", "it"), jedis.hgetAll("user:1"));
            assertEquals(2, jedis.sadd("tags", "java", "redis", "java"));
            assertEquals(Set.of("java", "redis"), jedis.smembers("tags"));
            assertEquals("string", jedis.type("greeting"));
            assertEquals(Set.of("user:1"), jedis.keys("user:*"));
            assertThrows(JedisDataException.class, () -> jedis.get("queue"));
            assertTrue(jedis.info().contains("loomkv_version:"));
            jedis.clientSetname("tester");
            assertEquals("tester", jedis.clientGetname());
        }
    }

    @Test
    void keysExpire() throws Exception {
        try (Jedis jedis = client()) {
            jedis.psetex("short", 150, "v");
            assertEquals("v", jedis.get("short"));
            Thread.sleep(300);
            assertNull(jedis.get("short"));
            assertEquals(-2, jedis.ttl("short"));
        }
    }

    @Test
    void pipeliningTenThousandCommands() {
        try (Jedis jedis = client()) {
            Pipeline pipeline = jedis.pipelined();
            List<Response<Long>> replies = new ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
                replies.add(pipeline.incr("hits"));
            }
            pipeline.sync();
            assertEquals(10_000, replies.getLast().get());
            assertEquals("10000", jedis.get("hits"));
        }
    }

    @Test
    void concurrentClientsSeeAtomicIncrements() throws Exception {
        int clients = 200, increments = 250;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < clients; c++) {
                executor.submit(() -> {
                    try (Jedis jedis = client()) {
                        for (int i = 0; i < increments; i++) {
                            jedis.incr("shared");
                            jedis.rpush("events", "x");
                        }
                    }
                    return null;
                });
            }
        }
        try (Jedis jedis = client()) {
            assertEquals(String.valueOf(clients * increments), jedis.get("shared"));
            assertEquals(clients * increments, jedis.llen("events"));
        }
    }

    @Test
    void publishSubscribe() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        JedisPubSub listener = new JedisPubSub() {
            @Override
            public void onSubscribe(String channel, int count) {
                subscribed.countDown();
            }

            @Override
            public void onMessage(String channel, String message) {
                received.add(channel + ":" + message);
                done.countDown();
            }
        };
        Thread subscriber = Thread.ofVirtual().start(() -> {
            try (Jedis jedis = client()) {
                jedis.subscribe(listener, "news", "alerts");
            }
        });
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        Thread.sleep(100); // let the second channel subscription land too
        try (Jedis publisher = client()) {
            assertEquals(1, publisher.publish("news", "one"));
            assertEquals(1, publisher.publish("alerts", "two"));
            assertEquals(0, publisher.publish("nobody", "lost"));
            assertEquals(1, publisher.publish("news", "three"));
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("news:one", "alerts:two", "news:three"), received);
        listener.unsubscribe();
        subscriber.join(5000);
    }

    @Test
    void appendOnlyFileSurvivesARestart() throws Exception {
        server.close();
        Path aof = dir.resolve("data.aof");
        Server.Options options = new Server.Options("127.0.0.1", 0, aof, AppendOnlyFile.Fsync.ALWAYS);
        server = new Server(options).start();
        try (Jedis jedis = client()) {
            jedis.set("kept", "yes");
            jedis.setex("ttl", 1000, "v");
            jedis.rpush("list", "1", "2");
            jedis.incr("n");
            jedis.set("deleted", "x");
            jedis.del("deleted");
        }
        server.close();

        server = new Server(options).start();
        assertEquals(6, server.replayedCommands());
        try (Jedis jedis = client()) {
            assertEquals("yes", jedis.get("kept"));
            assertTrue(jedis.ttl("ttl") > 990);
            assertEquals(List.of("1", "2"), jedis.lrange("list", 0, -1));
            assertEquals("1", jedis.get("n"));
            assertNull(jedis.get("deleted"));
        }
        assertTrue(Files.size(aof) > 0);
    }

    @Test
    void anIncompleteWriteAtTheEndOfTheFileIsCutOff() throws Exception {
        server.close();
        Path aof = dir.resolve("crash.aof");
        Files.writeString(aof, "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n*3\r\n$3\r\nSET\r\n$1\r\nj\r\n$10\r\nhal");
        server = new Server(new Server.Options("127.0.0.1", 0, aof, AppendOnlyFile.Fsync.ALWAYS)).start();
        assertEquals(1, server.replayedCommands());
        assertTrue(server.truncatedBytes() > 0);
        try (Jedis jedis = client()) {
            assertEquals("v", jedis.get("k"));
            jedis.set("after", "crash");
        }
        server.close();
        server = new Server(new Server.Options("127.0.0.1", 0, aof, AppendOnlyFile.Fsync.ALWAYS)).start();
        assertEquals(2, server.replayedCommands());
        assertEquals(0, server.truncatedBytes());
    }

    @Test
    void subscribedClientsCanOnlySubscribeOrPing() throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
            var out = socket.getOutputStream();
            var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
            out.write("SUBSCRIBE ch\r\nGET k\r\nPING\r\n".getBytes());
            out.flush();
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                lines.add(in.readLine());
            }
            assertEquals(List.of("*3", "$9", "subscribe", "$2", "ch", ":1"), lines.subList(0, 6));
            assertTrue(lines.get(6).startsWith("-ERR Can't execute 'get'"));
            assertEquals(List.of("*2", "$4", "pong", "$0", ""), lines.subList(7, 12));
        }
    }
}
