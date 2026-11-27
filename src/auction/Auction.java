package auction;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One auction room.
 *
 * Each auction has its own single-thread executor (a queue with one worker thread).
 * Every bid and the final close are submitted to it, so they run one at a time
 * in arrival order. Because only that one thread changes top / closed,
 * these fields need no extra locking (slide 9: "bids are processed in arrival order").
 */
public class Auction {

    final String id;
    final String item;
    final String seller;
    final long endTimeMillis;

    /** Current price and leader together. Immutable, so they always belong to the same bid. */
    record Top(long price, String leader) {}

    // Written only by this auction's thread, as one new object per bid. volatile so LIST / JOIN on
    // handler threads see the latest one. Readers copy it once (Top t = a.top) and use t, so they
    // never mix the price of one bid with the leader of another.
    volatile Top top;        // price is the start price and leader "-" until the first bid
    volatile boolean closed; // volatile: read by LIST / JOIN threads too

    final ExecutorService queue;

    Auction(String id, String item, String seller, long startPrice, int durationSeconds) {
        this.id = id;
        this.item = item;
        this.seller = seller;
        this.top = new Top(startPrice, "-");
        this.endTimeMillis = System.currentTimeMillis() + durationSeconds * 1000L;
        this.queue = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "auction-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    boolean hasLeader() {
        return !top.leader().equals("-");
    }

    /** Seconds until the end, rounded up. At least 1 while the auction is still open. */
    long secondsLeft() {
        long ms = endTimeMillis - System.currentTimeMillis();
        return Math.max(1, (ms + 999) / 1000);
    }
}
