package protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One protocol message (slides 7-8).
 *
 * <pre>
 * &lt;TYPE&gt; &lt;id | event&gt; [arguments] CRLF   first line
 * &lt;Name&gt;: &lt;value&gt; CRLF                  zero or more headers
 * CRLF                                   empty line ends the message
 *
 * REQ 17 BID          RES 17 200 OK       EVT OUTBID
 * Auction: item1      Auction: item1      Auction: item1
 * Amount: 50          Price: 50           Price: 50
 * </pre>
 *
 * Owner: 정유성 (protocol). Change this class only after the team agrees.
 */
public final class Message {

    public enum Type { REQ, RES, EVT }

    public static final String CRLF = "\r\n";

    private final Type type;
    private final String token;              // request id (REQ, RES) or event name (EVT)
    private final List<String> args;         // REQ: [command], RES: [code, reason...]
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Message(Type type, String token, List<String> args) {
        this.type = type;
        this.token = token;
        this.args = args;
    }

    // ---------- factories ----------

    public static Message req(int id, String command) {
        return new Message(Type.REQ, String.valueOf(id), List.of(command.toUpperCase()));
    }

    public static Message res(int id, int code, String reason) {
        return new Message(Type.RES, String.valueOf(id), List.of(String.valueOf(code), reason));
    }

    public static Message evt(String event) {
        return new Message(Type.EVT, event.toUpperCase(), List.of());
    }

    /** Adds a header and returns this message, so calls can be chained. */
    public Message header(String name, Object value) {
        headers.put(name, String.valueOf(value));
        return this;
    }

    // ---------- accessors ----------

    public Type type() { return type; }

    /** Request id of a REQ or RES. */
    public int id() {
        if (type == Type.EVT) throw new IllegalStateException("EVT has no id");
        return Integer.parseInt(token);
    }

    /** Command of a REQ, e.g. "BID". */
    public String command() { return type == Type.REQ ? args.get(0) : null; }

    /** Status code of a RES, e.g. 200. */
    public int code() { return type == Type.RES ? Integer.parseInt(args.get(0)) : -1; }

    /** Reason phrase of a RES, e.g. "OK". */
    public String reason() { return type == Type.RES ? String.join(" ", args.subList(1, args.size())) : null; }

    /** Event name of an EVT, e.g. "OUTBID". */
    public String event() { return type == Type.EVT ? token : null; }

    public String header(String name) { return headers.get(name); }

    public Map<String, String> headers() { return Collections.unmodifiableMap(headers); }

    /** Header as a number; throws ProtocolException (400) when missing or not a number. */
    public long longHeader(String name) throws ProtocolException {
        String v = requireHeader(name);
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new ProtocolException(name + " must be a number");
        }
    }

    /** Header value; throws ProtocolException (400) when missing. */
    public String requireHeader(String name) throws ProtocolException {
        String v = headers.get(name);
        if (v == null || v.isBlank()) throw new ProtocolException("Missing header: " + name);
        return v.trim();
    }

    // ---------- wire format ----------

    /** Text exactly as it goes on the TCP connection, including the final empty line. */
    public String toWire() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append(' ').append(token);
        for (String a : args) sb.append(' ').append(a);
        sb.append(CRLF);
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append(CRLF));
        sb.append(CRLF);
        return sb.toString();
    }

    /** Parses the lines of one message (without the final empty line). */
    public static Message parse(List<String> lines) throws ProtocolException {
        if (lines.isEmpty()) throw new ProtocolException("Empty message");
        String[] first = lines.get(0).trim().split(" +");
        if (first.length < 2) throw new ProtocolException("Bad first line: " + lines.get(0));

        Type type;
        try {
            type = Type.valueOf(first[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Unknown type: " + first[0]);
        }

        List<String> args = new ArrayList<>(Arrays.asList(first).subList(2, first.length));
        if (type == Type.REQ && args.isEmpty()) throw new ProtocolException("REQ without command");
        if (type == Type.RES && args.isEmpty()) throw new ProtocolException("RES without code");
        if (type != Type.EVT) {
            try {
                Integer.parseInt(first[1]);
            } catch (NumberFormatException e) {
                throw new ProtocolException("Bad id: " + first[1]);
            }
        }
        if (type == Type.REQ) args.set(0, args.get(0).toUpperCase());

        Message m = new Message(type, type == Type.EVT ? first[1].toUpperCase() : first[1], args);
        for (String line : lines.subList(1, lines.size())) {
            int colon = line.indexOf(':');
            if (colon <= 0) throw new ProtocolException("Bad header: " + line);
            m.headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return m;
    }

    @Override
    public String toString() {
        return String.join(" | ", toWire().strip().split(CRLF));
    }
}
