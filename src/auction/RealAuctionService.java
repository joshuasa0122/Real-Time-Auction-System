package auction;

import protocol.Message;

import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The real auction logic (owner: 변예서).
 *
 * Rules:
 *  - Every new user starts with 1000.
 *  - A bid must be higher than the current price (the first bid may equal the start price).
 *  - The seller cannot bid on their own auction.
 *  - Bidding holds the money. When someone is outbid, their hold is released.
 *    If the leader raises their own bid, only the difference is held.
 *  - When time is up the winner pays and the seller receives the price.
 *  - If a user disconnects, their bid stays valid.
 *
 * Threads:
 *  - Client handler threads call these methods at the same time.
 *  - Bids for one auction go through that auction's queue (see Auction), so they run in order.
 *  - One scheduler thread fires CLOSING (10 s before the end) and the close.
 */
public class RealAuctionService implements AuctionService {

    public static final long START_BALANCE = 1000;
    private static final int CLOSING_WARNING_SECONDS = 10;

    private final EventSink events;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Auction> auctions = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auction-timer");
        t.setDaemon(true);
        return t;
    });

    public RealAuctionService(EventSink events) {
        this.events = events;
    }

    /** Returns the user's account, creating it the first time (computeIfAbsent is thread-safe). */
    private Account account(String user) {
        return accounts.computeIfAbsent(user, name -> new Account(name, START_BALANCE));
    }

    // ---------------------------------------------------------------- LOGIN

    @Override
    public Result login(String user) {
        return Result.ok().header("Balance", account(user).available());
    }

    // ---------------------------------------------------------------- LIST

    @Override
    public Result list() {
        StringJoiner sj = new StringJoiner(",");
        for (Auction a : auctions.values()) {
            if (!a.closed) sj.add(a.id + ":" + a.top.price() + ":" + a.secondsLeft());
        }
        return Result.ok().header("Auctions", sj.toString());
    }

    // ---------------------------------------------------------------- CREATE

    @Override
    public Result create(String seller, String item, long startPrice, int durationSeconds) {
        if (item == null || item.isBlank()) return Result.badRequest("Item must not be empty");
        if (startPrice <= 0) return Result.badRequest("Price must be positive");
        if (durationSeconds <= 0) return Result.badRequest("Duration must be positive");

        String id = "a" + nextId.getAndIncrement();
        Auction a = new Auction(id, item, seller, startPrice, durationSeconds);
        auctions.put(id, a);

        // CLOSING warning, only if the auction is longer than the warning time
        if (durationSeconds > CLOSING_WARNING_SECONDS) {
            timer.schedule(() -> sendClosing(a),
                    durationSeconds - CLOSING_WARNING_SECONDS, TimeUnit.SECONDS);
        }
        // The close itself goes into the auction's queue, so it cannot run in the middle of a bid
        timer.schedule(() -> a.queue.submit(() -> close(a)), durationSeconds, TimeUnit.SECONDS);

        return Result.ok().header("Auction", id);
    }

    private void sendClosing(Auction a) {
        if (a.closed) return;
        events.toAuction(a.id, Message.evt("CLOSING")
                .header("Auction", a.id)
                .header("SecondsLeft", CLOSING_WARNING_SECONDS));
    }

    // ---------------------------------------------------------------- JOIN

    @Override
    public Result join(String user, String auctionId) {
        Auction a = auctions.get(auctionId);
        if (a == null || a.closed) return Result.notFound("No open auction " + auctionId);
        Auction.Top t = a.top;                       // one read: price and leader of the same bid
        return Result.ok()
                .header("Auction", a.id)
                .header("Item", a.item)
                .header("Price", t.price())
                .header("Leader", t.leader())
                .header("SecondsLeft", a.secondsLeft());
    }

    // ---------------------------------------------------------------- BID

    @Override
    public Result bid(String user, String auctionId, long amount) {
        Auction a = auctions.get(auctionId);
        if (a == null || a.closed) return Result.notFound("No open auction " + auctionId);

        // Put the bid in the auction's queue and wait for its turn to finish.
        Future<Result> f;
        try {
            f = a.queue.submit(() -> processBid(a, user, amount));
        } catch (RejectedExecutionException e) {      // queue already shut down = auction closed
            return Result.notFound("Auction " + auctionId + " is closed");
        }
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for bid", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());   // ClientHandler turns this into 500
        }
    }

    /** Runs only on the auction's own thread, one bid at a time. */
    private Result processBid(Auction a, String user, long amount) {
        if (a.closed) return Result.notFound("Auction " + a.id + " is closed");
        if (user.equals(a.seller)) return Result.badRequest("Seller cannot bid on own auction");

        Auction.Top t = a.top;
        boolean tooLow = a.hasLeader() ? amount <= t.price() : amount < t.price();
        if (tooLow) return Result.conflict("Bid too low, must be higher than " + t.price());

        Account bidder = account(user);
        boolean raisingOwnBid = user.equals(t.leader());
        long needed = raisingOwnBid ? amount - t.price() : amount;
        if (!bidder.hold(needed)) {
            return Result.conflict("Not enough balance (available " + bidder.available() + ")");
        }

        String previous = t.leader();
        long previousPrice = t.price();
        a.top = new Auction.Top(amount, user);   // price and leader change in one write

        // Give the previous leader their money back and tell them
        if (!previous.equals("-") && !raisingOwnBid) {
            Account prev = account(previous);
            prev.release(previousPrice);
            events.toUser(previous, Message.evt("OUTBID")
                    .header("Auction", a.id)
                    .header("Price", amount)
                    .header("Leader", user)
                    .header("Balance", prev.available()));
        }
        events.toAuction(a.id, Message.evt("NEW_BID")
                .header("Auction", a.id)
                .header("Price", amount)
                .header("Leader", user));

        return Result.ok()
                .header("Auction", a.id)
                .header("Price", amount)
                .header("Balance", bidder.available());
    }

    // ---------------------------------------------------------------- CLOSE

    /** Runs on the auction's own thread, after every bid that arrived before the end. */
    private void close(Auction a) {
        a.closed = true;
        a.queue.shutdown();   // later bids are rejected; bids already queued see closed = true
        Auction.Top t = a.top;

        events.toAuction(a.id, Message.evt("CLOSED")
                .header("Auction", a.id)
                .header("Winner", t.leader())     // "-" when nobody bid
                .header("Price", t.price()));

        if (a.hasLeader()) {
            Account winner = account(t.leader());
            Account seller = account(a.seller);
            winner.pay(t.price());
            seller.receive(t.price());
            events.toUser(t.leader(), Message.evt("WON")
                    .header("Auction", a.id)
                    .header("Price", t.price())
                    .header("Balance", winner.available()));
            events.toUser(a.seller, Message.evt("SOLD")
                    .header("Auction", a.id)
                    .header("Price", t.price())
                    .header("Balance", seller.available()));
        }
        auctions.remove(a.id);
    }
}
