// The auction UI: one state object and one render(). Event handlers change state, then call render().
// All communication goes through AuctionClient; the only source of truth is the server.
//
// Owner: 정유성 (web).

import { AuctionClient } from "./protocol.js";

const $ = (id) => document.getElementById(id);
const num = (v) => Number(v).toLocaleString("en-US");

const client = new AuctionClient();
const state = {
  screen: "login",        // login | lobby | room
  connected: true,
  user: null,
  balance: null,
  busy: false,            // a request from a button is running
  auctions: [],           // [{ id, price }] from LIST
  deadlines: new Map(),   // auction id -> local time (ms) when it ends
  rooms: new Map(),       // auction id -> room; kept after leaving because events keep coming
  roomId: null,
  feed: [],               // newest first
  errors: {},             // login | lobby | create | bid -> text
};

const newRoom = (id) => ({ id, item: "", price: 0, leader: "-", mine: false, seller: false, closing: false, ended: false, winner: null });
const isUser = (name) => name && name !== "-";

// ---------------------------------------------------------------- server -> state

/**
 * Prices only go up, so an older or repeated update changes nothing. This is what makes the order
 * of RES and EVT irrelevant (NEW_BID may arrive before the RES of the same bid).
 */
function applyPrice(room, price, leader) {
  price = Number(price);
  if (room.ended || !(price >= room.price)) return;
  if (price === room.price && !isUser(leader)) return;
  room.price = price;
  if (leader) room.leader = leader;
}

function setSecondsLeft(id, seconds) {
  // 0 means "unknown" from the stub and "about to close" from the real service: keep what we have.
  if (Number(seconds) > 0) state.deadlines.set(id, Date.now() + Number(seconds) * 1000);
}

function handleEvent({ event, headers: h }) {
  const room = state.rooms.get(h.Auction);
  if (room) {
    if (event === "NEW_BID" || event === "OUTBID") applyPrice(room, h.Price, h.Leader);
    if (event === "OUTBID") room.mine = true;
    if (event === "CLOSING") {
      room.closing = true;
      setSecondsLeft(h.Auction, h.SecondsLeft);
    }
    if (event === "CLOSED") end(room, h.Winner, h.Price);
    if (event === "WON") end(room, state.user, h.Price);
    if (event === "SOLD") {
      room.seller = true;
      end(room, room.winner ?? room.leader, h.Price);
    }
  }
  if (h.Balance !== undefined) state.balance = h.Balance;
  state.feed.unshift({ time: new Date().toLocaleTimeString("en-GB"), event, text: describe(event, h) });
  state.feed.length = Math.min(state.feed.length, 100);
  render();
}

function end(room, winner, price) {
  room.ended = true;
  room.winner = isUser(winner) ? winner : "-";
  if (price !== undefined) room.price = Number(price);
}

function describe(event, h) {
  switch (event) {
    case "NEW_BID": return `${h.Auction}: ${h.Leader} bid ${num(h.Price)}`;
    case "OUTBID": return `${h.Auction}: ${h.Leader} outbid you with ${num(h.Price)}`;
    case "CLOSING": return `${h.Auction}: closing in ${h.SecondsLeft} seconds`;
    case "CLOSED": return isUser(h.Winner) ? `${h.Auction}: won by ${h.Winner} for ${num(h.Price)}` : `${h.Auction}: closed without a winner`;
    case "WON": return `${h.Auction}: you won for ${num(h.Price)}`;
    case "SOLD": return `${h.Auction}: your item sold for ${num(h.Price)}`;
    default: return h.Auction ?? "";
  }
}

// ---------------------------------------------------------------- requests

function errorText(res, room) {
  const reason = res.headers.Error || res.reason;
  if (res.code === 404) return "This auction does not exist or has already ended.";
  if (res.code === 409 && /balance|money|fund|enough/i.test(reason)) return "You do not have enough balance for this bid.";
  if (res.code === 409 && room && /higher|low/i.test(reason)) return `Your bid is too low. The current price is ${num(room.price)}.`;
  if (res.code === 500) return "The server could not process this request. Try again.";
  return reason;
}

/** Runs one button action: buttons are disabled meanwhile and a lost connection becomes an error text. */
async function action(where, run) {
  if (state.busy) return;
  state.busy = true;
  state.errors[where] = "";
  render();
  try {
    await run((text) => { state.errors[where] = text; });
  } catch (e) {
    state.errors[where] = "The connection to the server was lost.";
  } finally {
    state.busy = false;
    render();
  }
}

async function refresh() {
  const res = await client.request("LIST");
  if (res.code !== 200) { state.errors.lobby = errorText(res); return render(); }
  state.errors.lobby = "";
  state.auctions = (res.headers.Auctions || "").split(",").filter(Boolean).map((entry) => {
    const [id, price, seconds] = entry.split(":");
    setSecondsLeft(id, seconds);
    return { id, price: Number(price) };
  });
  // A bid made while our JOIN was running may have been missed: ask for the current state again.
  const room = state.rooms.get(state.roomId);
  const listed = room && state.auctions.find((a) => a.id === room.id);
  if (listed && !room.ended && listed.price > room.price) await join(room.id);
  render();
}

/** Registers the room before asking, so events that overtake the RES are not lost. */
async function join(id) {
  const known = state.rooms.has(id);
  const room = state.rooms.get(id) ?? newRoom(id);
  state.rooms.set(id, room);
  let res;
  try {
    res = await client.request("JOIN", { Auction: id });
  } finally {
    if (res?.code !== 200 && !known) state.rooms.delete(id);
  }
  if (res.code === 200) {
    room.item = res.headers.Item || id;
    applyPrice(room, res.headers.Price, res.headers.Leader);
  }
  return res;
}

function enter(id, where) {
  return action(where, async (fail) => {
    if (!state.rooms.get(id)?.ended) {
      const res = await join(id);
      if (res.code !== 200) { fail(errorText(res)); return; }
    }
    openRoom(id);
  });
}

function openRoom(id) {
  state.roomId = id;
  state.screen = "room";
  state.errors.bid = "";
  $("amount").value = "";
}

function bid(amount) {
  const room = state.rooms.get(state.roomId);
  return action("bid", async (fail) => {
    const res = await client.request("BID", { Auction: room.id, Amount: amount });
    if (res.code !== 200) { fail(errorText(res, room)); return; }
    room.mine = true;
    applyPrice(room, res.headers.Price, state.user);
    $("amount").value = "";
  });
}

// ---------------------------------------------------------------- state -> screen

/** Text of the status box and its CSS class. */
function status(room) {
  if (room.ended) {
    const balance = `Your balance: ${num(state.balance)}`;
    if (!isUser(room.winner)) return ["ended", `Auction closed. No winner. ${balance}`];
    if (room.winner === state.user) return ["ended", `You won for ${num(room.price)}. ${balance}`];
    if (room.seller) return ["ended", `Sold to ${room.winner} for ${num(room.price)}. ${balance}`];
    return ["ended", `Auction closed. ${room.winner} won for ${num(room.price)}. ${balance}`];
  }
  if (room.seller) return ["", "You are the seller"];
  if (room.leader === state.user) return ["leading", "You are the highest bidder"];
  if (room.mine) return ["outbid", `You have been outbid by ${room.leader}`];
  return ["", "Watching. Place a bid to take part"];
}

const setText = (id, text) => { if ($(id).textContent !== String(text)) $(id).textContent = text; };

function el(tag, text, className = "") {
  const e = document.createElement(tag);
  e.textContent = text;
  e.className = className;
  return e;
}

/** Replaces a list only when its data changed, so focus and scrolling survive the periodic refresh. */
function replaceIfChanged(id, data, build) {
  const key = JSON.stringify(data);
  if ($(id).dataset.key === key) return;
  $(id).dataset.key = key;
  $(id).replaceChildren(...data.map(build));
}

function auctionRow({ id, price }) {
  const tr = document.createElement("tr");
  const time = el("td", "");
  time.dataset.clock = id;
  const button = el("button", "Join");
  button.setAttribute("aria-label", `Join ${id}`);
  const last = document.createElement("td");
  last.append(button);
  tr.append(el("td", id), el("td", num(price)), time, last);
  tr.addEventListener("click", () => enter(id, "lobby"));   // the button's click bubbles up to here
  return tr;
}

function feedItem({ time, event, text }) {
  const li = el("li", "", event);
  li.append(el("time", time), el("b", event), el("p", text));
  return li;
}

function secondsLeft(id) {
  const d = state.deadlines.get(id);
  return d === undefined ? null : Math.max(0, Math.ceil((d - Date.now()) / 1000));
}

function clock(id) {
  const s = secondsLeft(id);
  return s === null ? "--:--" : `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

function renderClocks() {
  for (const td of document.querySelectorAll("[data-clock]")) td.textContent = clock(td.dataset.clock);
  const room = state.rooms.get(state.roomId);
  if (room) {
    setText("room-time", room.ended ? "Closed" : clock(room.id));
    $("room-time").classList.toggle("closing", room.closing && !room.ended);
  }
}

function render() {
  for (const s of ["login", "lobby", "room"]) $(s).hidden = state.screen !== s;
  $("banner").hidden = state.connected;
  $("account").hidden = state.user === null;
  setText("user", state.user ?? "");
  setText("balance", num(state.balance ?? 0));
  for (const where of ["login", "lobby", "create", "bid"]) setText(`${where}-error`, state.errors[where] ?? "");

  replaceIfChanged("auctions", state.auctions, auctionRow);
  $("empty").hidden = state.auctions.length > 0;
  replaceIfChanged("feed", state.feed, feedItem);

  const room = state.rooms.get(state.roomId);
  if (room) {
    setText("room-item", room.item);
    setText("room-id", room.id);
    setText("room-price", num(room.price));
    $("room-price").classList.toggle("long", num(room.price).length > 7);
    setText("room-leader", isUser(room.leader) ? room.leader : "none yet");
    const [cls, text] = status(room);
    $("status").className = cls;
    setText("status", text);
    $("amount").min = room.price + 1;
    $("amount").placeholder = `${room.price + 1} or more`;
  }
  renderClocks();

  const blocked = state.busy || !state.connected;
  for (const b of document.querySelectorAll("main button")) b.disabled = blocked;
  for (const b of document.querySelectorAll("#bid-form button, #amount")) b.disabled = blocked || !room || room.ended || room.seller;
  $("amount").disabled = !state.connected || !room || room.ended || room.seller;   // keep focus while a bid is sent
  $("leave").disabled = !state.connected;
  for (const b of document.querySelectorAll("#dev button")) b.disabled = false;
}

// ---------------------------------------------------------------- user input

$("login-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const name = $("name").value.trim();
  if (!name) return;
  action("login", async (fail) => {
    const res = await client.request("LOGIN", { Name: name });
    if (res.code !== 200) { fail(errorText(res)); return; }
    state.user = name;
    state.balance = res.headers.Balance;
    state.screen = "lobby";
    await refresh();
  });
});

$("create-form").addEventListener("submit", (e) => {
  e.preventDefault();
  action("create", async (fail) => {
    const duration = $("duration").value;
    const res = await client.request("CREATE", { Item: $("item").value.trim(), Price: $("price").value, Duration: duration });
    if (res.code !== 200) { fail(errorText(res)); return; }
    const id = res.headers.Auction;
    setSecondsLeft(id, duration);
    const joined = await join(id);
    if (joined.code !== 200) { fail(errorText(joined)); return; }
    state.rooms.get(id).seller = true;
    $("create-form").reset();
    openRoom(id);
  });
});

$("bid-form").addEventListener("submit", (e) => {
  e.preventDefault();
  bid($("amount").value);
});
for (const b of document.querySelectorAll("#quick button")) {
  b.addEventListener("click", () => bid(state.rooms.get(state.roomId).price + Number(b.dataset.add)));
}

const refreshQuietly = () => refresh().catch(() => {});   // a lost connection is shown by the banner
$("refresh").addEventListener("click", refreshQuietly);
$("leave").addEventListener("click", () => {
  state.screen = "lobby";
  state.roomId = null;
  render();
  refreshQuietly();
});

// Development only: open the page with ?dev to fake events the server cannot send yet.
$("dev").hidden = !new URLSearchParams(location.search).has("dev");
for (const b of document.querySelectorAll("#dev button")) {
  b.addEventListener("click", () => {
    const room = state.rooms.get(state.roomId);
    const base = { Auction: room.id, Price: room.price };
    handleEvent({ event: b.textContent, headers: {
      OUTBID: { Auction: room.id, Price: room.price + 10, Leader: "someone" },
      CLOSING: { Auction: room.id, SecondsLeft: 10 },
      CLOSED: { ...base, Winner: room.leader },
      WON: { ...base, Balance: Number(state.balance) - room.price },
      SOLD: { ...base, Balance: Number(state.balance) + room.price },
    }[b.textContent] });
  });
}

// ---------------------------------------------------------------- start

client.onEvent(handleEvent);
client.onClose(() => { state.connected = false; render(); });
setInterval(renderClocks, 250);
setInterval(() => { if (state.user !== null && state.connected) refreshQuietly(); }, 5000);
render();
client.connect().catch(() => { state.connected = false; render(); });
