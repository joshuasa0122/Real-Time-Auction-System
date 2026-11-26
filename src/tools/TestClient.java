package tools;

import protocol.Message;
import protocol.MessageIO;
import protocol.ProtocolException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Console client of the auction system.
 * Two threads, as on slide 5: main thread reads the keyboard, a receiver thread shows everything
 * the server sends (RES and EVT) the moment it arrives. Each RES is matched to its REQ by the
 * request id (slide 7).
 *
 * Run: java -cp out tools.TestClient [host] [port]
 * Commands:
 *   login NAME | list | create ITEM PRICE SECONDS | join AUCTION | bid AUCTION AMOUNT | quit
 *
 * Owner: 전의령.
 */
public class TestClient {

    private static final String USAGE =
            "  usage: login NAME | list | create ITEM PRICE SECONDS | join AUCTION | bid AUCTION AMOUNT | quit";
    private static final int REPLY_WAIT_MILLIS = 3000;

    private final Map<Integer, Message> pending = new ConcurrentHashMap<>();   // id -> REQ without RES yet
    private volatile boolean closing;                                          // true while we quit
    private volatile String user;                                              // null until LOGIN succeeds

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        new TestClient().run(host, port);
    }

    private void run(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            Thread receiver = new Thread(() -> receive(in), "receiver");
            receiver.setDaemon(true);
            receiver.start();

            System.out.println("connected to " + host + ":" + port + ". Type a command.");
            System.out.println(USAGE);
            readKeyboard(out);

            waitForReplies();
            closing = true;                  // from here on a closed socket is expected
            System.out.println("bye");
        } catch (IOException e) {
            System.out.println("cannot connect to the server: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- keyboard -> server

    private void readKeyboard(Writer out) throws IOException {
        AtomicInteger nextId = new AtomicInteger(1);
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = keyboard.readLine()) != null) {
            String[] p = line.trim().split(" +");
            if (p[0].isEmpty()) continue;
            if (p[0].equalsIgnoreCase("quit")) break;
            Message req = build(nextId.get(), p);
            if (req == null) {
                System.out.println(USAGE);
                continue;
            }
            int id = nextId.getAndIncrement();
            pending.put(id, req);            // before writing: the RES may come back at once
            try {
                MessageIO.write(out, req);
                System.out.println("  -> " + req);
            } catch (IOException e) {
                pending.remove(id);
                System.out.println("  send failed: connection lost");
                break;
            }
        }
    }

    /** Builds the REQ for one command line, or null when the line is not valid. */
    private static Message build(int id, String[] p) {
        return switch (p[0].toLowerCase()) {
            case "login" -> p.length != 2 ? null : Message.req(id, "LOGIN").header("Name", p[1]);
            case "list" -> p.length != 1 ? null : Message.req(id, "LIST");
            case "create" -> p.length < 4 ? null : Message.req(id, "CREATE")
                    .header("Item", String.join(" ", Arrays.copyOfRange(p, 1, p.length - 2)))   // may contain spaces
                    .header("Price", p[p.length - 2])
                    .header("Duration", p[p.length - 1]);
            case "join" -> p.length != 2 ? null : Message.req(id, "JOIN").header("Auction", p[1]);
            case "bid" -> p.length != 3 ? null : Message.req(id, "BID").header("Auction", p[1]).header("Amount", p[2]);
            default -> null;
        };
    }

    /** After the last command, gives the server a moment to answer before the socket is closed. */
    private void waitForReplies() {
        long deadline = System.currentTimeMillis() + REPLY_WAIT_MILLIS;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- server -> screen

    private void receive(BufferedReader in) {
        String problem = "server closed the connection";
        try {
            Message m;
            while ((m = MessageIO.read(in)) != null) {
                if (m.type() == Message.Type.RES) showResponse(m);
                else if (m.type() == Message.Type.EVT) showEvent(m);
            }
        } catch (IOException e) {
            problem = "connection lost: " + e.getMessage();
        } catch (ProtocolException e) {
            problem = "bad message from the server: " + e.getMessage();
        }
        if (!closing) {
            System.out.println("  " + problem);
            System.exit(1);                  // the main thread is blocked on the keyboard
        }
    }

    private void showResponse(Message res) {
        Message req = pending.get(res.id());
        String command = req == null ? "?" : req.command();
        String tag = "  <- [#" + res.id() + " " + command + "] ";

        if (res.code() != 200) {
            String why = res.header("Error") != null ? res.header("Error") : res.reason();
            System.out.println(tag + "failed " + res.code() + ": " + why);
        } else {
            String text = switch (command) {
                case "LOGIN" -> {
                    user = req.header("Name");
                    yield "logged in as " + user + balance(res);
                }
                case "LIST" -> auctionList(res.header("Auctions"));
                case "CREATE" -> "created auction " + res.header("Auction")
                        + " (type: join " + res.header("Auction") + ")";
                case "JOIN" -> "joined " + res.header("Auction") + " " + res.header("Item")
                        + ", price " + res.header("Price") + ", leader " + name(res.header("Leader"))
                        + (res.header("SecondsLeft") == null ? "" : ", " + res.header("SecondsLeft") + "s left");
                case "BID" -> "bid accepted: " + res.header("Auction") + " at " + res.header("Price") + balance(res);
                default -> res.toString();
            };
            System.out.println(tag + text);
        }
        pending.remove(res.id());            // after printing, so waitForReplies() does not return early
    }

    private void showEvent(Message e) {
        String auction = e.header("Auction");
        String text = switch (e.event()) {
            case "NEW_BID" -> "new bid " + e.header("Price") + " by " + name(e.header("Leader"));
            case "OUTBID" -> "you were outbid by " + name(e.header("Leader")) + " at " + e.header("Price") + balance(e);
            case "CLOSING" -> "closing in " + e.header("SecondsLeft") + " seconds";
            case "CLOSED" -> nobody(e.header("Winner")) ? "closed without a winner"
                    : "closed, winner " + name(e.header("Winner")) + " at " + e.header("Price");
            case "WON" -> "you won at " + e.header("Price") + balance(e);
            case "SOLD" -> "your item was sold at " + e.header("Price") + balance(e);
            default -> e.toString();
        };
        System.out.println("  ** " + e.event() + " " + auction + ": " + text);
    }

    // ---------------------------------------------------------------- text helpers

    private static String balance(Message m) {
        String b = m.header("Balance");
        return b == null ? "" : " (balance " + b + ")";
    }

    /** The server sends "-" when there is no leader or winner. */
    private static boolean nobody(String who) {
        return who == null || who.isEmpty() || who.equals("-");
    }

    /** Our own name is marked. */
    private String name(String who) {
        if (nobody(who)) return "none";
        return who.equals(user) ? who + " (you)" : who;
    }

    /** Turns "a1:50:32,a2:10:5" into one line per auction. */
    private static String auctionList(String auctions) {
        if (auctions == null || auctions.isEmpty()) return "no open auctions";
        StringBuilder sb = new StringBuilder("open auctions");
        for (String entry : auctions.split(",")) {
            String[] f = entry.split(":");
            if (f.length != 3) continue;
            sb.append(System.lineSeparator()).append("       ").append(f[0])
              .append("  price ").append(f[1])
              .append("  time left ").append(f[2].equals("0") ? "unknown" : f[2] + "s");
        }
        return sb.toString();
    }
}
