package io.github.andreaacanfora.loomkv.command;

import io.github.andreaacanfora.loomkv.store.Bytes;

/** What commands need from the server beyond the keyspace. */
public interface Broker {

    /** Delivers a message to the channel's subscribers and returns how many received it. */
    int publish(Bytes channel, byte[] message);

    /** Text for the INFO command, sections separated by blank lines. */
    String info();
}
