package gateway;

import protocol.Message;
import protocol.MessageIO;
import protocol.ProtocolException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between the browser and the auction server, using only the standard library.
 *
 *   browser tab  --WebSocket-->  WsGateway  --TCP-->  AuctionServer
 *
 * One browser tab = one WebSocket = one TCP connection to the server, so the server still sees
 * one client per user and nothing in the server changes. Messages are relayed unchanged:
 * the browser sends and receives the same REQ / RES / EVT text as the console client.
 *
 * The same port also serves the files in web/, so the whole UI is http://localhost:8081.
 * It binds to 127.0.0.1 only: nothing outside this computer can connect.
 *
 * Run: java -cp out gateway.WsGateway [webPort] [serverHost] [serverPort] [webDir]
 *      defaults: 8081 localhost 5000 web
 *
 * Owner: 전의령.
 */
public class WsGateway {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");

    private final String serverHost;
    private final int serverPort;
    private final Path webRoot;

    public WsGateway(String serverHost, int serverPort, Path webRoot) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.webRoot = webRoot.toAbsolutePath().normalize();
    }

    public static void main(String[] args) throws IOException {
        int webPort = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        String host = args.length > 1 ? args[1] : "localhost";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 5000;
        Path web = Path.of(args.length > 3 ? args[3] : "web");

        WsGateway g = new WsGateway(host, port, web);
        try (ServerSocket ss = new ServerSocket(webPort, 50, InetAddress.getLoopbackAddress())) {
            System.out.println("Gateway on http://localhost:" + webPort + "  ->  auction server " + host + ":" + port);
            System.out.println("Serving " + g.webRoot);
            int n = 0;
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> g.handle(s), "browser-" + (++n)).start();
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket browser) {
        try (browser) {
            InputStream in = browser.getInputStream();
            OutputStream out = browser.getOutputStream();

            String requestLine = readHttpLine(in);
            if (requestLine == null) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readHttpLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && headers.containsKey("sec-websocket-key")) {
                handshake(out, headers.get("sec-websocket-key"));
                bridge(in, out);
            } else {
                serveFile(out, path);
            }
        } catch (IOException e) {
            System.out.println("[" + Thread.currentThread().getName() + "] closed: " + e.getMessage());
        }
    }

    private void serveFile(OutputStream out, String path) throws IOException {
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/")) path = "/index.html";
        Path file = webRoot.resolve(path.substring(1)).normalize();

        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {   // blocks ../ tricks
            byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.substring(name.lastIndexOf('.') + 1);
        byte[] body = Files.readAllBytes(file);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + TYPES.getOrDefault(ext, "application/octet-stream")
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** Reads one CRLF-terminated line byte by byte, so no bytes of the WebSocket stream are buffered away. */
    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.US_ASCII);
    }

    // ---------------------------------------------------------------- WebSocket (RFC 6455)

    private static void handshake(OutputStream out, String key) throws IOException {
        String accept;
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            accept = Base64.getEncoder().encodeToString(sha1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Relays between one WebSocket and one new TCP connection to the auction server. */
    private void bridge(InputStream wsIn, OutputStream wsOut) throws IOException {
        String name = Thread.currentThread().getName();
        try (Socket server = new Socket(serverHost, serverPort)) {
            System.out.println("[" + name + "] bridged to " + serverHost + ":" + serverPort);
            Writer toServer = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader fromServer = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

            // receiver thread: server -> browser, one WebSocket text frame per protocol message
            Thread receiver = new Thread(() -> {
                try {
                    Message m;
                    while ((m = MessageIO.read(fromServer)) != null) {
                        sendText(wsOut, m.toWire());
                    }
                } catch (IOException | ProtocolException e) {
                    // connection closed
                } finally {
                    try { sendClose(wsOut); } catch (IOException ignored) {}
                }
            }, name + "-receiver");
            receiver.setDaemon(true);
            receiver.start();

            // this thread: browser -> server
            ByteArrayOutputStream partial = new ByteArrayOutputStream();
            while (true) {
                int b0 = wsIn.read();
                if (b0 == -1) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                byte[] payload = readPayload(wsIn);

                if (opcode == 0x8) {                       // close
                    sendClose(wsOut);
                    break;
                } else if (opcode == 0x9) {                // ping -> pong
                    sendFrame(wsOut, 0xA, payload);
                } else if (opcode == 0x1 || opcode == 0x0) {  // text or continuation
                    partial.write(payload);
                    if (fin) {
                        String text = partial.toString(StandardCharsets.UTF_8);
                        partial.reset();
                        toServer.write(text);              // already in REQ wire format, ends with CRLF CRLF
                        toServer.flush();
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[" + name + "] cannot reach auction server: " + e.getMessage());
            try { sendClose(wsOut); } catch (IOException ignored) {}
        }
        System.out.println("[" + name + "] browser disconnected");
    }

    private static byte[] readPayload(InputStream in) throws IOException {
        int b1 = readByte(in);
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        if (len > 1_000_000) throw new IOException("frame too large");
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        byte[] data = new byte[(int) len];
        readFully(in, data);
        if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
        return data;
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) throw new EOFException();
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new EOFException();
            off += n;
        }
    }

    private static void sendText(OutputStream out, String text) throws IOException {
        sendFrame(out, 0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendClose(OutputStream out) throws IOException {
        sendFrame(out, 0x8, new byte[0]);
    }

    /** Server-to-browser frames are never masked. Synchronized so two threads cannot interleave frames. */
    private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        synchronized (out) {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                out.write(126);
                out.write(payload.length >>> 8);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) ((long) payload.length >>> (8 * i)) & 0xFF);
            }
            out.write(payload);
            out.flush();
        }
    }
}
