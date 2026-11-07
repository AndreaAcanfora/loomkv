package io.github.andreaacanfora.loomkv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andreaacanfora.loomkv.server.AppendOnlyFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void parsesOptions() {
        var options = Main.parse(new String[] {"--port", "7000", "--bind", "0.0.0.0", "--aof", "data.aof", "--appendfsync", "always"});
        assertEquals(7000, options.port());
        assertEquals("0.0.0.0", options.bind());
        assertEquals(Path.of("data.aof"), options.aof());
        assertEquals(AppendOnlyFile.Fsync.ALWAYS, options.fsync());
        var defaults = Main.parse(new String[0]);
        assertEquals(6379, defaults.port());
        assertNull(defaults.aof());
    }

    @Test
    void rejectsBadOptions() {
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {"--port", "99999"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {"--port"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {"--appendfsync", "sometimes"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parse(new String[] {"--turbo"}));
    }
}
