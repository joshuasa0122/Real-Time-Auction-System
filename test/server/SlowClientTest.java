package server;

import auction.AuctionService;
import auction.StubAuctionService;
import protocol.Message;
import protocol.MessageIO;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A client that joins and never reads must not delay anyone else, and is disconnected in the end.
 * Also checks that a connection reset frees the name.
 *
 * Runs the server with StubAuctionService (unlimited money, the seller may bid) because this tests
 * the network side only, not the auction rules.
 *
 * Run: javac -d out --release 17 $(find src test -name "*.java") && java -cp out server.SlowClientTest
 *
 * Owner: 정유성.
 */
public class SlowClientTest {

    static final int PORT = 5999;

    static class Client {
        final Socket s = new Socket();
        final BufferedReader in;
        final Writer out;
        int id;

        Client(boolean slow) throws Exception {
            if (slow) s.setReceiveBufferSize(1024);
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT));
            s.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
        }

        void send(String cmd, String... h) throws Exception {
            Message m = Message.req(++id, cmd);
            for (int i = 0; i < h.length; i += 2) m.header(h[i], h[i + 1]);
            MessageIO.write(out, m);
        }

        Message call(String cmd, String... h) throws Exception {
            send(cmd, h);
            Message m;
            do { m = MessageIO.read(in); } while (m != null && m.type() != Message.Type.RES);
            return m;
        }

        Message event(String name) throws Exception {
            Message m;
            do { m = MessageIO.read(in); } while (m != null && !name.equals(m.event()));
            return m;
        }
    }

    static void check(boolean ok, String what) {
        System.err.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }

    /** Retries until the server has noticed that the old connection is gone. */
    static boolean canLogin(String name) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (new Client(false).call("LOGIN", "Name", name).code() == 200) return true;
            Thread.sleep(20);
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        Thread server = new Thread(() -> {
            SessionRegistry sessions = new SessionRegistry();
            AuctionService stub = new StubAuctionService(new NetworkEventSink(sessions));
            try (ServerSocket ss = new ServerSocket(PORT)) {             // same accept loop as AuctionServer
                for (int n = 1; ; n++) new Thread(new ClientHandler(n, ss.accept(), stub, sessions), "handler-" + n).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        server.setDaemon(true);
        server.start();
        Thread.sleep(500);

        Client fast = new Client(false), watcher = new Client(false), slow = new Client(true);
        fast.call("LOGIN", "Name", "fast");
        watcher.call("LOGIN", "Name", "watcher");
        check(new Client(false).call("LOGIN", "Name", "fast").code() == 409, "duplicate name -> 409");
        check(fast.call("CREATE", "Item", "x", "Price", "1", "Duration", "4294967297").code() == 400, "Duration overflow -> 400");
        String a = fast.call("CREATE", "Item", "x", "Price", "1", "Duration", "60").header("Auction");
        watcher.call("JOIN", "Auction", a);
        slow.call("LOGIN", "Name", "slow");
        slow.call("JOIN", "Auction", a);           // from here on "slow" never reads

        long worst = 0;
        for (int p = 2; p < 30000; p++) {
            long t = System.nanoTime();
            check2(fast.call("BID", "Auction", a, "Amount", "" + p).code() == 200, p);
            Message e = watcher.event("NEW_BID");
            check2(e != null && e.longHeader("Price") == p, p);
            worst = Math.max(worst, System.nanoTime() - t);
        }
        check(worst < 1_000_000_000L, "30000 bids, worst NEW_BID delay " + worst / 1_000_000 + " ms");
        check(canLogin("slow"), "slow client was disconnected and its name is free again");

        Client gone = new Client(false);
        gone.call("LOGIN", "Name", "gone");
        gone.call("JOIN", "Auction", a);
        gone.s.setSoLinger(true, 0);               // close with RST, like a crashed client
        gone.s.close();
        fast.call("BID", "Auction", a, "Amount", "50000");
        check(watcher.event("NEW_BID") != null, "others still get NEW_BID after a reset");
        check(canLogin("gone"), "name of the reset client is free again");

        Thread.sleep(300);
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("writer-4 slow")
                || t.getName().equals("writer-3 slow")), "writer thread of the slow client ended");
        System.exit(0);
    }

    private static void check2(boolean ok, int price) {
        if (!ok) check(false, "bid " + price);
    }
}
