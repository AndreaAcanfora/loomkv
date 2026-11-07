package io.github.andreaacanfora.loomkv;

import io.github.andreaacanfora.loomkv.server.AppendOnlyFile;
import io.github.andreaacanfora.loomkv.server.Server;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Command-line entry point. */
public final class Main {

    private static final String USAGE = """
            loomkv: a Redis-compatible key-value server, one virtual thread per client.

            Usage: java -jar loomkv.jar [options]
              --port <n>              port to listen on (default 6379)
              --bind <address>        address to bind (default 127.0.0.1; 0.0.0.0 for all interfaces)
              --aof <file>            persist writes to an append-only file and replay it on start
              --appendfsync <policy>  always | everysec (default) | no
              --version               print the version
              --help                  show this help
            """;

    private Main() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        Server.Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
            return;
        }
        if (options == null) {
            return;
        }
        Server server = new Server(options).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
                System.out.println("loomkv: stopped, data flushed");
            } catch (IOException e) {
                System.err.println("loomkv: error while stopping: " + e.getMessage());
            }
        }));
        System.out.printf("loomkv %s on %s:%d (Java %s, virtual threads)%s%n", Server.VERSION, options.bind(),
                server.port(), Runtime.version().feature(),
                options.aof() == null ? ", memory only"
                        : ", AOF %s: %d commands replayed%s".formatted(options.aof(), server.replayedCommands(),
                                server.truncatedBytes() > 0 ? ", truncated " + server.truncatedBytes() + " bytes of an incomplete write" : ""));
        Thread.currentThread().join(); // run until stopped
    }

    static Server.Options parse(String[] args) {
        Server.Options o = Server.Options.defaults();
        String bind = o.bind();
        int port = o.port();
        Path aof = null;
        AppendOnlyFile.Fsync fsync = o.fsync();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    System.out.print(USAGE);
                    return null;
                }
                case "--version", "-v" -> {
                    System.out.println("loomkv " + Server.VERSION);
                    return null;
                }
                case "--port", "--bind", "--aof", "--appendfsync" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(arg + " needs a value");
                    }
                    String value = args[++i];
                    switch (arg) {
                        case "--port" -> {
                            try {
                                port = Integer.parseInt(value);
                            } catch (NumberFormatException e) {
                                throw new IllegalArgumentException("invalid port " + value);
                            }
                            if (port < 0 || port > 65535) {
                                throw new IllegalArgumentException("invalid port " + value);
                            }
                        }
                        case "--bind" -> bind = value;
                        case "--aof" -> aof = Path.of(value);
                        default -> {
                            try {
                                fsync = AppendOnlyFile.Fsync.valueOf(value.toUpperCase(Locale.ROOT));
                            } catch (IllegalArgumentException e) {
                                throw new IllegalArgumentException("appendfsync must be always, everysec or no");
                            }
                        }
                    }
                }
                default -> throw new IllegalArgumentException("unknown option " + arg);
            }
        }
        return new Server.Options(bind, port, aof, fsync);
    }
}
