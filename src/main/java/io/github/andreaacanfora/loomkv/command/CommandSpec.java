package io.github.andreaacanfora.loomkv.command;

import io.github.andreaacanfora.loomkv.resp.RespValue;

/**
 * A command definition. Arity follows Redis: it counts the command name, and a negative arity means
 * "at least" (SET is -3: SET key value [options...]).
 */
public record CommandSpec(String name, int arity, boolean write, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        RespValue handle(Context ctx) throws CommandException;
    }

    public boolean arityOk(int argc) {
        return arity >= 0 ? argc == arity : argc >= -arity;
    }
}
