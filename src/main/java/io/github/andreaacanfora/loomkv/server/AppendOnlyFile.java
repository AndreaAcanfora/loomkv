package io.github.andreaacanfora.loomkv.server;

import io.github.andreaacanfora.loomkv.resp.RespReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;

/**
 * Persistence as a log of write commands, in the same RESP format clients send, like Redis' AOF.
 * On startup the log is replayed to rebuild memory.
 */
public final class AppendOnlyFile implements Closeable {

    /** When data reaches the disk: after every write, once a second, or whenever the OS decides. */
    public enum Fsync { ALWAYS, EVERYSEC, NO }

    private final FileOutputStream file;
    private final OutputStream out;
    private final Fsync fsync;
    private final Thread flusher;
    private volatile boolean closed;

    public AppendOnlyFile(Path path, Fsync fsync) throws IOException {
        this.file = new FileOutputStream(path.toFile(), true);
        this.out = new BufferedOutputStream(file, 64 * 1024);
        this.fsync = fsync;
        this.flusher = fsync == Fsync.ALWAYS ? null : Thread.ofVirtual().name("aof-flush").start(this::flushLoop);
    }

    /** Appends one command. Called with the database lock held, so the file follows memory order. */
    public synchronized void append(List<byte[]> command) {
        try {
            writeCommand(out, command);
            if (fsync == Fsync.ALWAYS) {
                out.flush();
                file.getFD().sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the append-only file", e);
        }
    }

    static void writeCommand(OutputStream out, List<byte[]> command) throws IOException {
        out.write(("*" + command.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] arg : command) {
            out.write(("$" + arg.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(arg);
            out.write('\r');
            out.write('\n');
        }
    }

    private void flushLoop() {
        while (!closed) {
            try {
                Thread.sleep(1000);
                flush(fsync == Fsync.EVERYSEC);
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                System.err.println("loomkv: AOF flush failed: " + e.getMessage());
            }
        }
    }

    private synchronized void flush(boolean sync) throws IOException {
        out.flush();
        if (sync) {
            file.getFD().sync();
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (flusher != null) {
            flusher.interrupt();
        }
        flush(true);
        out.close();
    }

    /** What replay found. */
    public record ReplayResult(long commands, long truncatedBytes) {}

    /**
     * Replays every complete command. If the server died in the middle of a write, the incomplete tail is
     * cut off, so new commands are appended after valid data.
     */
    public static ReplayResult replay(Path path, Consumer<List<byte[]>> apply) throws IOException {
        if (!Files.exists(path)) {
            return new ReplayResult(0, 0);
        }
        long size = Files.size(path);
        long commands = 0;
        long validUpTo = 0;
        try (CountingInputStream counting = new CountingInputStream(new BufferedInputStream(Files.newInputStream(path), 64 * 1024))) {
            RespReader reader = new RespReader(counting);
            while (true) {
                List<byte[]> command;
                try {
                    command = reader.readCommand();
                } catch (IOException e) {
                    break; // malformed or truncated: keep what was read before it
                }
                if (command == null) {
                    break;
                }
                apply.accept(command);
                commands++;
                validUpTo = counting.count;
            }
        }
        long truncated = size - validUpTo;
        if (truncated > 0) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.truncate(validUpTo);
            }
        }
        return new ReplayResult(commands, truncated);
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }
}
