package auction;

/**
 * One user's money.
 *
 *   balance   = all the money the user owns
 *   held      = part of balance locked because the user is leading some auction
 *   available = balance - held  (what the user can still bid with)
 *
 * Every method is synchronized, so two threads (for example two auctions the
 * same user is bidding in) cannot change the numbers at the same time.
 * No method ever locks a second account, so there is no deadlock risk.
 */
public class Account {

    private final String name;
    private long balance;
    private long held;

    public Account(String name, long startBalance) {
        this.name = name;
        this.balance = startBalance;
    }

    public String name() { return name; }

    public synchronized long available() {
        return balance - held;
    }

    /** Locks money for a bid. Returns false if the user does not have enough. */
    public synchronized boolean hold(long amount) {
        if (amount > available()) return false;
        held += amount;
        return true;
    }

    /** Unlocks money, for example when the user is outbid. */
    public synchronized void release(long amount) {
        held -= amount;
    }

    /** The winner pays: the held money leaves the account for good. */
    public synchronized void pay(long amount) {
        held -= amount;
        balance -= amount;
    }

    /** The seller receives the winning price. */
    public synchronized void receive(long amount) {
        balance += amount;
    }
}
