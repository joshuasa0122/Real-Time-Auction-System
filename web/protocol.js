// Browser side of the auction protocol (slides 7-8).
// Builds REQ text, parses RES / EVT text, and matches each RES to its request by id.
// The UI code only calls request() and onEvent(); it never touches raw text.
//
// Owner: 정유성 (web).

const CRLF = "\r\n";

/** Turns protocol text into { type, id, command, code, reason, event, headers }. */
export function parseMessage(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.length > 0);
  const first = lines[0].trim().split(/ +/);
  const msg = { type: first[0], headers: {} };
  if (msg.type === "EVT") {
    msg.event = first[1];
  } else {
    msg.id = Number(first[1]);
    if (msg.type === "RES") {
      msg.code = Number(first[2]);
      msg.reason = first.slice(3).join(" ");
    } else {
      msg.command = first[2];
    }
  }
  for (const line of lines.slice(1)) {
    const i = line.indexOf(":");
    if (i > 0) msg.headers[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return msg;
}

/** Builds REQ text ending with the empty line. */
export function formatRequest(id, command, headers = {}) {
  let s = `REQ ${id} ${command.toUpperCase()}${CRLF}`;
  for (const [k, v] of Object.entries(headers)) s += `${k}: ${v}${CRLF}`;
  return s + CRLF;
}

export class AuctionClient {
  constructor(url = `ws://${location.host}/ws`) {
    this.url = url;
    this.nextId = 1;
    this.pending = new Map();      // id -> { resolve, reject }
    this.eventHandlers = new Set();
    this.closeHandlers = new Set();
  }

  /** Opens the WebSocket. Resolves when connected. */
  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url);
      this.ws.onopen = () => resolve();
      this.ws.onerror = () => reject(new Error("Cannot reach the gateway"));
      this.ws.onmessage = (e) => this.#receive(e.data);
      this.ws.onclose = () => {
        for (const p of this.pending.values()) p.reject(new Error("Connection closed"));
        this.pending.clear();
        this.closeHandlers.forEach((h) => h());
      };
    });
  }

  /**
   * Sends a REQ and resolves with the matching RES: { code, reason, headers }.
   * Resolves for every status code; check res.code === 200 in the UI.
   */
  request(command, headers = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      // send() on a closed WebSocket fails silently, so the RES would never come
      if (this.ws?.readyState !== WebSocket.OPEN) return reject(new Error("Not connected"));
      this.pending.set(id, { resolve, reject });
      this.ws.send(formatRequest(id, command, headers));
    });
  }

  /** Subscribes to server events: handler({ event, headers }). Returns an unsubscribe function. */
  onEvent(handler) {
    this.eventHandlers.add(handler);
    return () => this.eventHandlers.delete(handler);
  }

  onClose(handler) {
    this.closeHandlers.add(handler);
  }

  #receive(text) {
    const msg = parseMessage(text);
    if (msg.type === "RES") {
      const p = this.pending.get(msg.id);
      if (p) {
        this.pending.delete(msg.id);
        p.resolve(msg);
      }
    } else if (msg.type === "EVT") {
      this.eventHandlers.forEach((h) => h(msg));
    }
  }
}
