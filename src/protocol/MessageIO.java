package protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes whole messages on a connection. Shared by server, test client and gateway. */
public final class MessageIO {

    private MessageIO() {}

    /**
     * Blocks until one full message (ending with an empty line) has arrived.
     * Empty lines between messages are skipped.
     *
     * @return the message, or null when the other side closed the connection
     */
    public static Message read(BufferedReader in) throws IOException, ProtocolException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {      // readLine strips CRLF or LF
            if (line.isEmpty()) {
                if (lines.isEmpty()) continue;           // tolerate extra blank lines
                return Message.parse(lines);
            }
            lines.add(line);
        }
        return null;                                     // EOF
    }

    /** Writes one message and flushes. Callers that share a writer must synchronize. */
    public static void write(Writer out, Message m) throws IOException {
        out.write(m.toWire());
        out.flush();
    }
}
