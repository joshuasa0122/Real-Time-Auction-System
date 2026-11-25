package auction;

import protocol.Message;

/**
 * How the auction logic pushes events without knowing about sockets (slide 11).
 * Implemented by the network side (server.NetworkEventSink).
 *
 * Events and who gets them (slide 8):
 *   toAuction: NEW_BID, CLOSING, CLOSED     every client that joined the auction
 *   toUser:    OUTBID, WON, SOLD            one user (previous leader, winner, seller)
 *
 * Build events with Message.evt("OUTBID").header("Auction", id).header("Price", 50) ...
 */
public interface EventSink {

    /** Sends the event to one logged-in user. Does nothing if that user is offline. */
    void toUser(String user, Message event);

    /** Sends the event to every user who joined the auction. */
    void toAuction(String auctionId, Message event);
}
