package io.github.andreaacanfora.loomkv.command;

import java.io.Serial;

/** Turns into an error reply; the message is sent as-is, so it starts with the error code. */
public final class CommandException extends Exception {
    @Serial private static final long serialVersionUID = 1L;

    public static final String WRONGTYPE = "WRONGTYPE Operation against a key holding the wrong kind of value";
    public static final String NOT_INTEGER = "ERR value is not an integer or out of range";
    public static final String SYNTAX = "ERR syntax error";

    public CommandException(String message) {
        super(message, null, false, false);
    }
}
