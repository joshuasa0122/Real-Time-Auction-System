package server;

import auction.AuctionService;
import auction.EventSink;
import auction.StubAuctionService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Entry point. The main thread only accepts connections and starts one handler thread each
 * (slide 5).
 *
 * Run: java -cp out server.AuctionServer [port]     default port 5000
 *
 * Owner: 정유성.
 */
public class AuctionServer {

    public static final int DEFAULT_PORT = 5000;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        SessionRegistry sessions = new SessionRegistry();
        EventSink events = new NetworkEventSink(sessions);
        AuctionService auctions = new StubAuctionService(events);   // TODO(변예서): swap in the real service

        try (ServerSocket server = new ServerSocket(port)) {
            Log.info("listening on port " + port);
            int n = 0;
            while (true) {
                Socket s = server.accept();
                ClientHandler handler = new ClientHandler(++n, s, auctions, sessions);
                new Thread(handler, "handler-" + n).start();
            }
        }
    }
}
