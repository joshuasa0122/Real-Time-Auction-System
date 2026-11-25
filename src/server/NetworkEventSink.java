package server;

import auction.EventSink;
import protocol.Message;

import java.util.Set;

/**
 * The network side of EventSink (slide 5, "Event broadcaster").
 * Looks up recipients in the SessionRegistry and puts the event in each one's outbound queue.
 * It runs on the auction logic's thread and never touches a socket, so it cannot be blocked by
 * a slow client.
 *
 * Owner: 정유성.
 */
public class NetworkEventSink implements EventSink {

    private final SessionRegistry sessions;

    public NetworkEventSink(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public void toUser(String user, Message event) {
        ClientHandler h = sessions.handlerOf(user);
        log(event, h != null ? user : user + " (offline)");
        if (h != null) h.send(event);
    }

    @Override
    public void toAuction(String auctionId, Message event) {
        Set<ClientHandler> members = sessions.membersOf(auctionId);
        log(event, members.size() + " users");
        for (ClientHandler h : members) h.send(event);
    }

    private static void log(Message event, String to) {
        Log.as("auction", "-> " + Log.format(event) + " to " + to + " (by " + Thread.currentThread().getName() + ")");
    }
}
