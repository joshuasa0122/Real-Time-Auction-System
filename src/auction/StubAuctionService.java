package auction;

import protocol.Message;

import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEMPORARY stand-in so the server, gateway and web page can run end to end today.
 * No accounts, no money holds, no bid queue, no close timer.
 *
 * TODO(변예서): replace with the real implementation (bid queue per auction,
 * hold / release / transfer, ScheduledExecutorService close timer, CLOSING / CLOSED / WON / SOLD).
 * Keep the constructor signature AuctionService(EventSink) so AuctionServer only changes one line.
 */
public class StubAuctionService implements AuctionService {

    private static final class Auction {
        final String id, item, seller;
        long price;
        String leader = "-";

        Auction(String id, String item, String seller, long price) {
            this.id = id;
            this.item = item;
            this.seller = seller;
            this.price = price;
        }
    }

    private final EventSink events;
    private final Map<String, Auction> auctions = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public StubAuctionService(EventSink events) {
        this.events = events;
    }

    @Override
    public Result login(String user) {
        return Result.ok().header("Balance", 1000);
    }

    @Override
    public Result list() {
        StringJoiner sj = new StringJoiner(",");
        auctions.values().forEach(a -> sj.add(a.id + ":" + a.price + ":0"));
        return Result.ok().header("Auctions", sj.toString());
    }

    @Override
    public Result create(String seller, String item, long startPrice, int durationSeconds) {
        if (startPrice <= 0 || durationSeconds <= 0) return Result.badRequest("Price and Duration must be positive");
        String id = "a" + nextId.getAndIncrement();
        auctions.put(id, new Auction(id, item, seller, startPrice));
        return Result.ok().header("Auction", id);
    }

    @Override
    public Result join(String user, String auctionId) {
        Auction a = auctions.get(auctionId);
        if (a == null) return Result.notFound("No auction " + auctionId);
        return Result.ok().header("Auction", a.id).header("Item", a.item).header("Price", a.price).header("Leader", a.leader);
    }

    @Override
    public Result bid(String user, String auctionId, long amount) {
        Auction a = auctions.get(auctionId);
        if (a == null) return Result.notFound("No auction " + auctionId);

        String previous;
        synchronized (a) {                       // the real version uses a per-auction queue instead
            if (amount <= a.price) return Result.conflict("Bid must be higher than " + a.price);
            previous = a.leader;
            a.price = amount;
            a.leader = user;
        }

        if (!previous.equals("-") && !previous.equals(user)) {
            events.toUser(previous, Message.evt("OUTBID").header("Auction", a.id).header("Price", amount).header("Leader", user));
        }
        events.toAuction(a.id, Message.evt("NEW_BID").header("Auction", a.id).header("Price", amount).header("Leader", user));
        return Result.ok().header("Auction", a.id).header("Price", amount);
    }
}
