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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Automatic bidder (slide 11, "bid bots"). It logs in, joins one auction and, whenever someone
 * else leads, bids STEP more after a short random delay, never above MAX. It stops when the
 * auction closes.
 *
 * Two threads: the main thread listens to the server, the "bidder" thread sends the delayed bids.
 * Both use the same fields, so the methods that touch them are synchronized.
 *
 * Run: java -cp out tools.BidBot NAME AUCTION MAX [STEP] [host] [port]
 *      e.g. java -cp out tools.BidBot bot1 a1 500 10
 */
public class BidBot {

    private static final int MIN_DELAY_MILLIS = 300;
    private static final int MAX_DELAY_MILLIS = 1500;
    private static final int RESULT_WAIT_MILLIS = 3000;

    private final String name;
    private final String auctionId;
    private final long maxPrice;
    private final long step;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bidder");
        t.setDaemon(true);
        return t;
    });

    private Socket socket;
    private Writer out;
    private int nextId = 1;
    private int loginId, joinId, bidId;
    private long price;                  // highest price seen so far
    private String leader = "-";         // "-" means nobody has bid yet
    private long bidAmount;              // amount of the bid that is waiting for its RES
    private long priceWhenSent;          // price at the moment that bid was sent
    private boolean joined;
    private boolean busy;                // a bid is scheduled or waiting for its RES
    private boolean done;

    public BidBot(String name, String auctionId, long maxPrice, long step) {
        this.name = name;
        this.auctionId = auctionId;
        this.maxPrice = maxPrice;
        this.step = step;
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            usage();
            return;
        }
        BidBot bot;
        String host;
        int port;
        try {
            long max = Long.parseLong(args[2]);
            long step = args.length > 3 ? Long.parseLong(args[3]) : 10;
            if (max <= 0 || step <= 0) {
                usage();
                return;
            }
            bot = new BidBot(args[0], args[1], max, step);
            host = args.length > 4 ? args[4] : "localhost";
            port = args.length > 5 ? Integer.parseInt(args[5]) : 5000;
        } catch (NumberFormatException e) {
            usage();
            return;
        }
        bot.run(host, port);
    }

    private static void usage() {
        System.out.println("usage: java -cp out tools.BidBot NAME AUCTION MAX [STEP] [host] [port]");
    }

    private void run(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            socket = s;
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            loginId = send(Message.req(nextId(), "LOGIN").header("Name", name));
            Message m;
            while (!isDone() && (m = MessageIO.read(in)) != null) {
                if (m.type() == Message.Type.RES) onResponse(m);
                else if (m.type() == Message.Type.EVT) onEvent(m);
            }
            if (!isDone()) log("server closed the connection");
        } catch (IOException | ProtocolException e) {
            log("stopped: " + e.getMessage());
        } finally {
            timer.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- server -> bot

    private synchronized void onResponse(Message res) throws IOException {
        int id = res.id();
        if (id == loginId) {
            if (res.code() != 200) {
                fail("login", res);
                return;
            }
            log("logged in, balance " + res.header("Balance"));
            joinId = send(Message.req(nextId(), "JOIN").header("Auction", auctionId));
        } else if (id == joinId) {
            if (res.code() != 200) {
                fail("join", res);
                return;
            }
            joined = true;
            update(Long.parseLong(res.header("Price")), res.header("Leader"));
            log("joined " + auctionId + ", price " + price + ", leader " + leader + ", my limit " + maxPrice);
            scheduleBid();
        } else if (id == bidId) {
            busy = false;
            if (res.code() == 200) {
                log("bid " + bidAmount + " accepted");
                update(bidAmount, name);
                scheduleBid();           // someone may have outbid us before this RES arrived
            } else {
                log("bid " + bidAmount + " failed " + res.code() + ": " + res.header("Error"));
                // Retry only if the price moved; otherwise (e.g. not enough money) wait for the next NEW_BID.
                if (price > priceWhenSent) scheduleBid();
            }
        }
    }

    private synchronized void onEvent(Message e) throws IOException {
        if (!auctionId.equals(e.header("Auction"))) return;
        switch (e.event()) {
            case "NEW_BID" -> {          // react here only: OUTBID reports the same bid
                update(Long.parseLong(e.header("Price")), e.header("Leader"));
                scheduleBid();
            }
            case "OUTBID" -> log("outbid by " + e.header("Leader") + " at " + e.header("Price"));
            case "CLOSING" -> log("closing in " + e.header("SecondsLeft") + " seconds");
            case "CLOSED" -> {
                log("closed, winner " + e.header("Winner") + ", price " + e.header("Price"));
                if (name.equals(e.header("Winner"))) {
                    joined = false;      // no more bids; WON follows
                    socket.setSoTimeout(RESULT_WAIT_MILLIS);
                } else {
                    done = true;
                }
            }
            case "WON" -> {
                log("won at " + e.header("Price") + ", balance " + e.header("Balance"));
                done = true;
            }
            default -> { }
        }
    }

    /** Prices only go up, so an older update is ignored. */
    private void update(long newPrice, String newLeader) {
        if (newPrice < price) return;
        if (newPrice == price && "-".equals(newLeader)) return;
        price = newPrice;
        if (newLeader != null) leader = newLeader;
    }

    // ---------------------------------------------------------------- bot -> server

    private synchronized void scheduleBid() {
        if (!joined || done || busy || leader.equals(name)) return;
        if (price >= maxPrice) {
            log("price " + price + " reached my limit, waiting for the auction to close");
            return;
        }
        busy = true;
        int delay = ThreadLocalRandom.current().nextInt(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS);
        timer.schedule(this::placeBid, delay, TimeUnit.MILLISECONDS);
    }

    /** Runs on the bidder thread. Uses the newest price, which may have changed during the delay. */
    private synchronized void placeBid() {
        if (!joined || done || leader.equals(name) || price >= maxPrice) {
            busy = false;
            return;
        }
        bidAmount = price + Math.min(step, maxPrice - price);
        priceWhenSent = price;
        try {
            bidId = send(Message.req(nextId(), "BID").header("Auction", auctionId).header("Amount", bidAmount));
            log("bid " + bidAmount);
        } catch (IOException e) {
            log("send failed: " + e.getMessage());
        }
    }

    private synchronized int nextId() {
        return nextId++;
    }

    private synchronized int send(Message req) throws IOException {
        MessageIO.write(out, req);
        return req.id();
    }

    // ---------------------------------------------------------------- helpers

    private synchronized boolean isDone() {
        return done;
    }

    private void fail(String what, Message res) {
        log(what + " failed " + res.code() + ": " + res.header("Error"));
        done = true;
    }

    private void log(String text) {
        System.out.println("[" + name + "] " + text);
    }
}
