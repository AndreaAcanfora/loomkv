package io.github.andreaacanfora.loomkv.server;

import io.github.andreaacanfora.loomkv.command.Context;
import io.github.andreaacanfora.loomkv.resp.ProtocolException;
import io.github.andreaacanfora.loomkv.resp.RespReader;
import io.github.andreaacanfora.loomkv.resp.RespValue;
import io.github.andreaacanfora.loomkv.resp.RespWriter;
import io.github.andreaacanfora.loomkv.store.Bytes;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One client, served by one virtual thread with plain blocking I/O. Replies are flushed only when the
 * client has nothing else queued, so pipelined requests get their replies in one write.
 */
final class Connection implements Runnable {

    /** Messages a slow subscriber may fall behind by before it is disconnected (like Redis' pubsub limit). */
    static final int MAX_PENDING_PUSHES = 10_000;

    private final Server server;
    private final Socket socket;
    private final long id;
    private final RespReader reader;
    private final RespWriter writer;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Set<Bytes> subscriptions = new LinkedHashSet<>();
    private final BlockingQueue<RespValue> pushes = new ArrayBlockingQueue<>(MAX_PENDING_PUSHES);
    private volatile Thread pusher;
    private volatile String name = "";
    private volatile boolean closed;

    Connection(Server server, Socket socket, long id) throws IOException {
        this.server = server;
        this.socket = socket;
        this.id = id;
        this.reader = new RespReader(new BufferedInputStream(socket.getInputStream(), 16 * 1024));
        this.writer = new RespWriter(new BufferedOutputStream(socket.getOutputStream(), 16 * 1024));
    }

    long id() {
        return id;
    }

    String describe() {
        return "id=%d addr=%s name=%s sub=%d".formatted(id, socket.getRemoteSocketAddress(), name, subscriptions.size());
    }

    @Override
    public void run() {
        try (socket) {
            while (!closed) {
                List<byte[]> command;
                try {
                    command = reader.readCommand();
                } catch (ProtocolException e) {
                    send(RespValue.error("ERR " + e.getMessage()), true);
                    break;
                }
                if (command == null) {
                    break;
                }
                if (command.isEmpty()) {
                    continue;
                }
                RespValue reply = handle(command);
                if (reply != null) {
                    send(reply, !reader.hasBuffered());
                }
            }
        } catch (IOException e) {
            // Client went away mid-request: nothing to do.
        } finally {
            close();
        }
    }

    private RespValue handle(List<byte[]> command) throws IOException {
        server.countCommand();
        String name = new String(command.getFirst(), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        if (!subscriptions.isEmpty() && !List.of("SUBSCRIBE", "UNSUBSCRIBE", "PING", "QUIT", "RESET").contains(name)) {
            return RespValue.error("ERR Can't execute '" + name.toLowerCase(Locale.ROOT)
                    + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context");
        }
        if (name.equals("PING") && !subscriptions.isEmpty()) {
            // In subscribed mode PING answers in the push format.
            return RespValue.array(List.of(
                    RespValue.bulk("pong"), RespValue.bulk(command.size() > 1 ? command.get(1) : new byte[0])));
        }
        return switch (name) {
            case "QUIT" -> {
                send(RespValue.OK, true);
                closed = true;
                yield null;
            }
            case "SUBSCRIBE" -> subscribe(command);
            case "UNSUBSCRIBE" -> unsubscribe(command);
            case "CLIENT" -> client(command);
            default -> server.commands().execute(new Context(server.database(), server, command), server::persist);
        };
    }

    private RespValue subscribe(List<byte[]> command) throws IOException {
        if (command.size() < 2) {
            return RespValue.error("ERR wrong number of arguments for 'subscribe' command");
        }
        startPusher();
        for (int i = 1; i < command.size(); i++) {
            Bytes channel = new Bytes(command.get(i));
            if (subscriptions.add(channel)) {
                server.pubSub().subscribe(this, channel);
            }
            send(RespValue.array(List.of(RespValue.bulk("subscribe"), RespValue.bulk(channel.bytes()),
                    RespValue.integer(subscriptions.size()))), i == command.size() - 1);
        }
        return null;
    }

    private RespValue unsubscribe(List<byte[]> command) throws IOException {
        List<Bytes> channels = new ArrayList<>();
        for (int i = 1; i < command.size(); i++) {
            channels.add(new Bytes(command.get(i)));
        }
        if (channels.isEmpty()) {
            channels.addAll(subscriptions);
        }
        if (channels.isEmpty()) {
            return RespValue.array(List.of(RespValue.bulk("unsubscribe"), RespValue.NIL, RespValue.ZERO));
        }
        for (int i = 0; i < channels.size(); i++) {
            Bytes channel = channels.get(i);
            subscriptions.remove(channel);
            server.pubSub().unsubscribe(this, channel);
            send(RespValue.array(List.of(RespValue.bulk("unsubscribe"), RespValue.bulk(channel.bytes()),
                    RespValue.integer(subscriptions.size()))), i == channels.size() - 1);
        }
        return null;
    }

    private RespValue client(List<byte[]> command) {
        String sub = command.size() > 1 ? new String(command.get(1), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT) : "";
        return switch (sub) {
            case "SETNAME" -> {
                if (command.size() != 3) {
                    yield RespValue.error("ERR wrong number of arguments for 'client|setname' command");
                }
                name = new String(command.get(2), StandardCharsets.UTF_8);
                yield RespValue.OK;
            }
            case "GETNAME" -> RespValue.bulk(name.isEmpty() ? null : name);
            case "ID" -> RespValue.integer(id);
            case "LIST" -> RespValue.bulk(server.clientList());
            case "SETINFO" -> RespValue.OK; // clients report their library name and version
            default -> RespValue.error("ERR unknown subcommand '" + sub.toLowerCase(Locale.ROOT) + "'");
        };
    }

    /**
     * Queues a pub/sub message. Never blocks the publisher: a subscriber that falls too far behind is
     * disconnected instead. Returns false if the message could not be queued.
     */
    boolean push(RespValue message) {
        if (closed) {
            return false;
        }
        if (!pushes.offer(message)) {
            System.err.println("loomkv: disconnecting slow subscriber " + describe());
            close();
            return false;
        }
        return true;
    }

    private void startPusher() {
        if (pusher == null) {
            pusher = Thread.ofVirtual().name("push-" + id).start(() -> {
                try {
                    while (!closed) {
                        RespValue message = pushes.take();
                        send(message, pushes.isEmpty());
                    }
                } catch (InterruptedException | IOException e) {
                    close();
                }
            });
        }
    }

    private void send(RespValue value, boolean flush) throws IOException {
        writeLock.lock();
        try {
            writer.write(value);
            if (flush) {
                writer.flush();
            }
        } finally {
            writeLock.unlock();
        }
    }

    void close() {
        if (closed && socket.isClosed()) {
            return;
        }
        closed = true;
        for (Bytes channel : List.copyOf(subscriptions)) {
            server.pubSub().unsubscribe(this, channel);
        }
        Thread p = pusher;
        if (p != null) {
            p.interrupt();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
        server.disconnected(this);
    }
}
