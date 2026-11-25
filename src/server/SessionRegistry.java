package server;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is online and who joined which auction (slide 5, "Session registry").
 * Thread-safe: every handler thread and the auction logic use it at the same time.
 *
 * Owner: 정유성.
 */
public class SessionRegistry {

    private final Map<String, ClientHandler> online = new ConcurrentHashMap<>();
    private final Map<String, Set<ClientHandler>> members = new ConcurrentHashMap<>();

    /** Registers a logged-in user. Returns false if the name is already online. */
    public boolean login(String user, ClientHandler handler) {
        return online.putIfAbsent(user, handler) == null;
    }

    public ClientHandler handlerOf(String user) {
        return online.get(user);
    }

    public void join(String auctionId, ClientHandler handler) {
        members.computeIfAbsent(auctionId, k -> ConcurrentHashMap.newKeySet()).add(handler);
    }

    public Set<ClientHandler> membersOf(String auctionId) {
        return members.getOrDefault(auctionId, Collections.emptySet());
    }

    /**
     * Called when a connection closes; safe to call twice. Membership is kept per connection, not
     * per name, so cleaning up an old connection cannot remove a user who already reconnected.
     */
    public void logout(String user, ClientHandler handler) {
        members.values().forEach(set -> set.remove(handler));
        if (user != null) online.remove(user, handler);
    }

    public int onlineCount() {
        return online.size();
    }
}
