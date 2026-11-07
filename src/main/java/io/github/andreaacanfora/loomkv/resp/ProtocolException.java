package io.github.andreaacanfora.loomkv.resp;

import java.io.IOException;
import java.io.Serial;

/** The client sent something that is not valid RESP; the connection is closed after replying. */
public final class ProtocolException extends IOException {
    @Serial private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super("Protocol error: " + message);
    }
}
