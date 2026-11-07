package io.github.andreaacanfora.loomkv.server;

import io.github.andreaacanfora.loomkv.command.Broker;
import io.github.andreaacanfora.loomkv.command.Commands;
import io.github.andreaacanfora.loomkv.command.Context;
import io.github.andreaacanfora.loomkv.store.Bytes;
import io.github.andreaacanfora.loomkv.store.Database;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** Accepts connections and gives each one its own virtual thread. */
public final class Server implements Broker, Closeable {

    /** Startup options. {@code aof} may be null (memory only). Port 0 picks a free port. */
    public record Options(String bind, int port, Path aof, AppendOnlyFile.Fsync fsync) {
        public static Options defaults() {
            return new Options("127.0.0.1", 6379, null, AppendOnlyFile.Fsync.EVERYSEC);
        }
    }

    public static final String VERSION = versionFromManifest();

    private final Options options;
    private final Database database = new Database();
    private final Commands commands = new Commands();
    private final PubSub pubSub = new PubSub();
    private final Map<Long, Connection> clients = new ConcurrentHashMap<>();
    private final AtomicLong nextClientId = new AtomicLong(1);
    private final LongAdder commandsProcessed = new LongAdder();
    private final AtomicLong connectionsReceived = new AtomicLong();
    private final Instant started = Instant.now();
    private ServerSocket listener;
    private AppendOnlyFile aof;
    private volatile boolean running;
    private long replayed;
    private long truncatedBytes;

    public Server(Options options) {
        this.options = options;
    }

    /** Replays the AOF, binds the port and starts accepting. Returns once the server is listening. */
    public Server start() throws IOException {
        if (options.aof() != null) {
            var result = AppendOnlyFile.replay(options.aof(),
                    command -> commands.execute(new Context(database, this, command), null));
            replayed = result.commands();
            truncatedBytes = result.truncatedBytes();
            aof = new AppendOnlyFile(options.aof(), options.fsync());
        }
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName(options.bind()), options.port()), 1024);
        running = true;
        Thread.ofVirtual().name("accept").start(this::acceptLoop);
        Thread.ofVirtual().name("expire").start(this::expireLoop);
        return this;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = listener.accept();
                socket.setTcpNoDelay(true);
                Connection connection = new Connection(this, socket, nextClientId.getAndIncrement());
                clients.put(connection.id(), connection);
                connectionsReceived.incrementAndGet();
                Thread.ofVirtual().name("client-" + connection.id()).start(connection);
            } catch (IOException e) {
                if (running) {
                    System.err.println("loomkv: accept failed: " + e.getMessage());
                }
            }
        }
    }

    /** Ten times a second, delete keys whose TTL has passed even if nobody reads them. */
    private void expireLoop() {
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            database.expireDue(1000);
        }
    }

    public int port() {
        return listener.getLocalPort();
    }

    public long replayedCommands() {
        return replayed;
    }

    public long truncatedBytes() {
        return truncatedBytes;
    }

    Database database() {
        return database;
    }

    Commands commands() {
        return commands;
    }

    PubSub pubSub() {
        return pubSub;
    }

    void persist(List<byte[]> command) {
        if (aof != null) {
            aof.append(command);
        }
    }

    void countCommand() {
        commandsProcessed.increment();
    }

    void disconnected(Connection connection) {
        clients.remove(connection.id());
    }

    String clientList() {
        return clients.values().stream().map(Connection::describe).collect(Collectors.joining("\n", "", "\n"));
    }

    @Override
    public int publish(Bytes channel, byte[] message) {
        return pubSub.publish(channel, message);
    }

    @Override
    public String info() {
        Runtime runtime = Runtime.getRuntime();
        long uptime = Duration.between(started, Instant.now()).toSeconds();
        int keys = database.locked(database::size);
        return String.join("\r\n",
                "# Server",
                "redis_version:7.4.0", // what clients compare against when checking features
                "loomkv_version:" + VERSION,
                "java_version:" + Runtime.version(),
                "process_id:" + ProcessHandle.current().pid(),
                "tcp_port:" + port(),
                "uptime_in_seconds:" + uptime,
                "",
                "# Clients",
                "connected_clients:" + clients.size(),
                "",
                "# Memory",
                "used_memory:" + (runtime.totalMemory() - runtime.freeMemory()),
                "maxmemory:" + runtime.maxMemory(),
                "",
                "# Persistence",
                "aof_enabled:" + (aof != null ? 1 : 0),
                "aof_replayed_commands:" + replayed,
                "",
                "# Stats",
                "total_connections_received:" + connectionsReceived.get(),
                "total_commands_processed:" + commandsProcessed.sum(),
                "expired_keys:" + database.locked(database::expiredKeys),
                "pubsub_channels:" + pubSub.channelCount(),
                "",
                "# Keyspace",
                keys > 0 ? "db0:keys=" + keys : "",
                "");
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (listener != null) {
            listener.close();
        }
        for (Connection connection : List.copyOf(clients.values())) {
            connection.close();
        }
        if (aof != null) {
            // Under the lock, so no command is half-written when the file closes.
            database.lock().lock();
            try {
                aof.close();
            } finally {
                database.lock().unlock();
            }
        }
    }

    private static String versionFromManifest() {
        String version = Server.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
