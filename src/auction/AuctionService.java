package auction;

/**
 * Boundary between the network side and the auction logic (slide 11).
 *
 * The network side (server package, 정유성) calls these methods from client handler threads.
 * The auction side (auction package, 변예서) implements them and never touches sockets.
 * To push events, the implementation calls {@link EventSink}, which it receives in its constructor.
 *
 * Every method may be called from many threads at once, so implementations must be thread-safe.
 * Header names used below are the team's agreed names; keep them in sync with README.md.
 */
public interface AuctionService {

    /**
     * REQ LOGIN, header Name. Creates the account on first login.
     * OK result should carry header Balance.
     */
    Result login(String user);

    /**
     * REQ LIST. OK result carries header Auctions, e.g. "item1:50:32,item2:10:5"
     * (auction id : current price : seconds left), or an empty value when none are open.
     */
    Result list();

    /**
     * REQ CREATE, headers Item, Price (starting price), Duration (seconds).
     * Starts the close timer. OK result carries header Auction (the new auction id).
     */
    Result create(String seller, String item, long startPrice, int durationSeconds);

    /**
     * REQ JOIN, header Auction. Only checks that the auction exists and is open (404 otherwise).
     * The network side records membership in its SessionRegistry after an OK result.
     * OK result should carry headers Price and Leader so the client can draw the current state.
     */
    Result join(String user, String auctionId);

    /**
     * REQ BID, headers Auction, Amount.
     * Must process bids for one auction in arrival order (slide 9), hold the bidder's money,
     * release the previous leader's hold, then push OUTBID and NEW_BID through the EventSink.
     * May block the calling handler thread until the bid has been processed.
     */
    Result bid(String user, String auctionId, long amount);
}
