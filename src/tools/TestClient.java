package tools;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Console client for testing the server without the web page, and the starting point for bid bots.
 * Two threads, as on slide 5: main thread reads the keyboard, a receiver thread prints everything
 * the server sends (RES and EVT) the moment it arrives.
 *
 * Run: java -cp out tools.TestClient [host] [port]
 * Commands:
 *   login NAME | list | create ITEM PRICE SECONDS | join AUCTION | bid AUCTION AMOUNT | quit
 *
 * Owner: 전의령 (bots and multi-client tests).
 */
public class TestClient {

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        try (Socket socket = new Socket(host, port)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            Thread receiver = new Thread(() -> {
                try {
                    Message m;
                    while ((m = MessageIO.read(in)) != null) {
                        System.out.println("  <- " + m);
                    }
                    System.out.println("  server closed the connection");
                } catch (IOException | ProtocolException e) {
                    System.out.println("  receiver stopped: " + e.getMessage());
                }
            }, "receiver");
            receiver.setDaemon(true);
            receiver.start();

            AtomicInteger nextId = new AtomicInteger(1);
            BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.println("connected to " + host + ":" + port + ". Type a command.");
            String line;
            while ((line = keyboard.readLine()) != null) {
                String[] p = line.trim().split(" +");
                if (p[0].isEmpty()) continue;
                if (p[0].equalsIgnoreCase("quit")) break;
                Message req = build(nextId.getAndIncrement(), p);
                if (req == null) {
                    System.out.println("  usage: login NAME | list | create ITEM PRICE SECONDS | join AUCTION | bid AUCTION AMOUNT | quit");
                    continue;
                }
                MessageIO.write(out, req);
                System.out.println("  -> " + req);
            }
        }
    }

    private static Message build(int id, String[] p) {
        try {
            return switch (p[0].toLowerCase()) {
                case "login" -> Message.req(id, "LOGIN").header("Name", p[1]);
                case "list" -> Message.req(id, "LIST");
                case "create" -> Message.req(id, "CREATE").header("Item", p[1]).header("Price", p[2]).header("Duration", p[3]);
                case "join" -> Message.req(id, "JOIN").header("Auction", p[1]);
                case "bid" -> Message.req(id, "BID").header("Auction", p[1]).header("Amount", p[2]);
                default -> null;
            };
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }
}
