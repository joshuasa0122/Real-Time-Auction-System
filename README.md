# Real-time Auction System

Computer Networking term project. Java 17, standard library only. Runs locally; nothing is deployed.

```
browser tab ──WebSocket──▶ WsGateway ──TCP──▶ AuctionServer ──▶ AuctionService (auction logic)
 (web/)        :8081         (gateway)          :5000             (auction)
                                 ▲
tools.TestClient ──TCP───────────┘ (console client / bid bots connect straight to :5000)
```

## Run (three terminals, from the project root)

```
javac -d out --release 17 $(find src -name "*.java")     # Windows: compile in Eclipse instead

java -cp out server.AuctionServer          # 1. server on :5000
java -cp out gateway.WsGateway             # 2. gateway + web page on http://localhost:8081
java -cp out tools.TestClient              # 3. optional console client (login NAME, create ..., bid ...)
```

macOS: AirPlay Receiver already uses port 5000, so the server stops with `Address already in use`.
Turn it off (System Settings > General > AirDrop & Handoff) or use another port everywhere:

```
java -cp out server.AuctionServer 5050
java -cp out gateway.WsGateway 8081 localhost 5050
java -cp out tools.TestClient localhost 5050
```

Open http://localhost:8081 in two or three browser tabs: each tab is one bidder.
In Eclipse: make `src` the source folder, run each main class, and set the working directory of
WsGateway to the project root so it finds `web/`.

## Who owns what

| Package / folder | Owner | Status |
|---|---|---|
| `protocol` (Message, MessageIO) | 정유성 | Done. Change only after the team agrees |
| `server` (accept, handler threads, SessionRegistry, NetworkEventSink) | 정유성 | Done: per-client queue and writer thread, disconnect handling, `Log`, `test/server/SlowClientTest` |
| `auction` (AuctionService, EventSink, RealAuctionService) | 변예서 | **Interfaces fixed.** Done: accounts, per-auction bid queue, close timer. `StubAuctionService` is only used by `SlowClientTest` |
| `gateway` (WsGateway) | 전의령 | Working relay; add reconnect / error display as needed |
| `tools` (TestClient) | 전의령 | Base for bid bots and multi-client tests |
| `web` (protocol.js, app.js, index.html, style.css) | 정유성 | Done: login, lobby, auction room. `?dev` shows fake-event buttons |

Rule from slide 11: nobody edits another member's package. Cross-package changes go through
`AuctionService` / `EventSink` and are agreed first.

## Protocol headers (agreed names)

| Request | Headers sent | OK response headers |
|---|---|---|
| `LOGIN` | `Name` | `Balance` |
| `LIST` | – | `Auctions` = `id:price:secondsLeft,...` |
| `CREATE` | `Item`, `Price`, `Duration` (s) | `Auction` |
| `JOIN` | `Auction` | `Auction`, `Item`, `Price`, `Leader`, `SecondsLeft` |
| `BID` | `Auction`, `Amount` | `Auction`, `Price`, `Balance` |

Errors: `400` bad arguments, not logged in, or the seller bidding on their own auction,
`404` unknown or closed auction, `409` bid too low / not enough money / name already online,
`500` bug on the server side. Every error carries an `Error` header with the reason.

Rules: every new user starts with 1000. `Balance` is the money still available, after the holds of
bids that are currently leading. The first bid may equal the start price; every later bid must be higher.
`Leader` / `Winner` is `-` when nobody has bid.

| Event | Sent to | Headers |
|---|---|---|
| `NEW_BID` | everyone in the auction | `Auction`, `Price`, `Leader` |
| `OUTBID` | previous leader | `Auction`, `Price`, `Leader`, `Balance` |
| `CLOSING` | everyone in the auction | `Auction`, `SecondsLeft` (10 s before the end; none for auctions of 10 s or less) |
| `CLOSED` | everyone in the auction | `Auction`, `Winner`, `Price` |
| `WON` / `SOLD` | winner / seller (only when there is a winner) | `Auction`, `Price`, `Balance` |

Note: `NEW_BID` reaches the bidder just before its own `RES`. Clients must not assume an order;
they match `RES` by id (slide 7). Events of one auction are sent from that auction's own thread
(`auction-a1`), so everyone receives them in the same order.

## Threads and the server log

Each connection has two threads: `handler-N` reads requests, `writer-N` is the only one writing to the
socket. `RES` and `EVT` share one outbound queue (256 messages); a client that lets it fill up is
disconnected, and nobody else waits for it.

```
[handler-3 bob] <- REQ 4 BID Auction=a1 Amount=110                                          received
[auction] -> EVT NEW_BID Auction=a1 Price=110 Leader=bob to 3 users (by handler-3 bob)      queued
[writer-4 carol] -> EVT NEW_BID Auction=a1 Price=110 Leader=bob                             written
```

Test: `javac -d out --release 17 $(find src test -name "*.java") && java -cp out server.SlowClientTest`

Open questions between members: `docs/requests.md`.

## Verified on 2026-11-26 (with RealAuctionService)

- Three browser tabs: create, join, bid, `OUTBID`, low bid → `409`, `CLOSING`, `CLOSED`, `WON`, `SOLD`, balances
- Holds: raising your own bid holds only the difference; being outbid releases the hold; not enough money → `409`
- 20 clients bidding at once on 3 auctions: same `NEW_BID` order for everyone, one winner, total money unchanged

## Verified on 2026-11-25

- Two console clients: login, create, join, bid, outbid → `OUTBID` and `NEW_BID` pushed, low bid → `409`
- Two WebSocket clients through the gateway: same flow, plus a malformed request → `400`
- `GET /` serves the page; `/../` path traversal → `404`; gateway listens on 127.0.0.1 only
- `SlowClientTest`: 30000 bids while one client never reads; no delay for others, only that client is dropped
- Three browser tabs (seller, two bidders): create, join, bid, `OUTBID`, low bid → `409`, one tab closed

## 협업

- 작업별 브랜치를 만들고 `main`으로 Pull Request를 연다.
- 클라이언트와 서버가 함께 쓰는 `REQ`, `RES`, `EVT` 형식은 변경 전에 팀에서 합의한다.
- 첫 통합 목표는 두 클라이언트를 연결해 한쪽의 입찰로 다른 쪽에 `EVT NEW_BID`가 표시되게 하는 것이다.
