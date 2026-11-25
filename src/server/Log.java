package server;

import protocol.Message;

/**
 * The one place the server prints to the console. Every line starts with the thread that did the
 * work, e.g. "[handler-3 bob] &lt;- REQ 5 BID Auction=a1 Amount=200".
 *
 * Owner: 정유성.
 */
final class Log {

    private Log() {}

    static void info(String text) {
        as(Thread.currentThread().getName(), text);
    }

    static void as(String who, String text) {
        System.out.println("[" + who + "] " + text);
    }

    /** One-line form of a message: first line, then Name=value for each header. */
    static String format(Message m) {
        String wire = m.toWire();
        StringBuilder sb = new StringBuilder(wire.substring(0, wire.indexOf(Message.CRLF)));
        m.headers().forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        return sb.toString();
    }
}
