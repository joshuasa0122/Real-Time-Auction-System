package server;

import auction.AuctionService;
import auction.Result;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two threads per client connection (slide 5):
 *   handler-N  reads REQ messages, calls AuctionService, queues the RES
 *   writer-N   the only thread that writes to the socket
 *
 * RES (from handler-N) and EVT (from any thread) go through the same outbound queue and one writer
 * takes whole messages from it, so two messages can never interleave on the socket.
 *
 * Owner: 정유성.
 */
public class ClientHandler implements Runnable {

    private static final int QUEUE_CAPACITY = 256;

    private final int number;
    private final Socket socket;
    private final AuctionService auctions;
    private final SessionRegistry sessions;
    private final BlockingQueue<Message> outbound = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread writer;
    private volatile String user;              // null until LOGIN succeeds

    public ClientHandler(int number, Socket socket, AuctionService auctions, SessionRegistry sessions) {
        this.number = number;
        this.socket = socket;
        this.auctions = auctions;
        this.sessions = sessions;
        this.writer = new Thread(this::writeLoop, "writer-" + number);
    }

    @Override
    public void run() {
        String peer = socket.getRemoteSocketAddress().toString();
        Log.info("connected " + peer);
        writer.start();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setTcpNoDelay(true);                       // push small events at once, no Nagle delay
            while (true) {
                Message req;
                try {
                    req = MessageIO.read(in);
                } catch (ProtocolException e) {
                    send(Message.res(0, 400, "Bad Request").header("Error", e.getMessage()));
                    continue;
                }
                if (req == null) break;                       // client closed the connection
                if (req.type() != Message.Type.REQ) {
                    send(Message.res(0, 400, "Bad Request").header("Error", "Clients may only send REQ"));
                    continue;
                }
                Log.info("<- " + Log.format(req));
                send(handle(req));
            }
        } catch (IOException e) {
            if (!closed.get()) Log.info("connection lost: " + e.getMessage());
        } finally {
            close();
            sessions.logout(user, this);      // again: a JOIN may have been running when close() was called
            Log.info("disconnected " + peer);
        }
    }

    /** Turns one request into one response. */
    private Message handle(Message req) {
        int id = req.id();
        try {
            String cmd = req.command();
            if (!cmd.equals("LOGIN") && user == null) {
                return toRes(id, Result.badRequest("LOGIN first"));
            }
            Result r = switch (cmd) {
                case "LOGIN" -> login(req.requireHeader("Name"));
                case "LIST" -> auctions.list();
                case "CREATE" -> auctions.create(user, req.requireHeader("Item"),
                        req.longHeader("Price"), intHeader(req, "Duration"));
                case "JOIN" -> join(req.requireHeader("Auction"));
                case "BID" -> auctions.bid(user, req.requireHeader("Auction"), req.longHeader("Amount"));
                default -> Result.badRequest("Unknown command " + cmd);
            };
            return toRes(id, r);
        } catch (ProtocolException e) {
            return toRes(id, Result.badRequest(e.getMessage()));
        } catch (RuntimeException e) {                       // a bug in the auction logic must not kill the connection
            Log.info("error handling " + Log.format(req) + ": " + e);
            return Message.res(id, 500, "Server Error").header("Error", e.getClass().getSimpleName());
        }
    }

    private Result login(String name) {
        if (user != null) return Result.conflict("Already logged in as " + user);
        if (!sessions.login(name, this)) return Result.conflict(name + " is already online");
        boolean ok = false;
        try {
            Result r = auctions.login(name);
            ok = r.isOk();
            return r;
        } finally {                           // also when auctions.login() throws, or the name stays taken
            if (ok) {
                user = name;
                Thread.currentThread().setName("handler-" + number + " " + name);
                writer.setName("writer-" + number + " " + name);
            } else {
                sessions.logout(name, this);
            }
        }
    }

    /** Rejects numbers that a plain (int) cast would silently wrap around. */
    private static int intHeader(Message req, String name) throws ProtocolException {
        long v = req.longHeader(name);
        if (v != (int) v) throw new ProtocolException(name + " is out of range");
        return (int) v;
    }

    private Result join(String auctionId) {
        Result r = auctions.join(user, auctionId);
        if (r.isOk()) sessions.join(auctionId, this);
        return r;
    }

    private static Message toRes(int id, Result r) {
        Message m = Message.res(id, r.code(), r.reason());
        r.headers().forEach(m::header);
        return m;
    }

    /**
     * Queues a message and returns at once; called by this handler and by NetworkEventSink.
     * A full queue means the client reads too slowly: only that client is disconnected.
     */
    public void send(Message m) {
        if (closed.get()) return;
        if (!outbound.offer(m) && close()) {
            Log.info("client too slow, disconnecting " + writer.getName() + " (" + QUEUE_CAPACITY + " messages waiting)");
        }
    }

    private void writeLoop() {
        try {
            Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            while (!closed.get()) {
                Message m = outbound.take();
                MessageIO.write(out, m);
                Log.info("-> " + Log.format(m));
            }
        } catch (InterruptedException e) {
            // close() was called
        } catch (IOException e) {
            if (close()) Log.info("send failed: " + e.getMessage());
        }
    }

    /** Ends the connection and both threads, from any thread. Returns false if already closed. */
    private boolean close() {
        if (!closed.compareAndSet(false, true)) return false;
        sessions.logout(user, this);
        try { socket.close(); } catch (IOException ignored) {}   // unblocks read() and write()
        writer.interrupt();                                      // unblocks take()
        return true;
    }
}
