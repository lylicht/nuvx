import { DurableObject } from "cloudflare:workers";

const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ";
const CODE_LENGTH = 6;
const MAX_BODY_BYTES = 16 * 1024;
const MAX_QUEUE = 26;
const MAX_UPCOMING = 25;
const ROOM_LIMIT = 50;
const EMPTY_TTL_MS = 120_000;
const MEMBER_GRACE_MS = 45_000;
const PARTY_MAX_AGE_MS = 12 * 60 * 60 * 1000;
const PLAY_LEAD_MS = 350;
const HEARTBEAT_MS = 5_000;
const MAX_SOCKET_AGE_MS = 15 * 60 * 1000;

interface Env {
  ROOMS: DurableObjectNamespace<PartyRoom>;
  REGISTRY: DurableObjectNamespace<Registry>;
  ALLOWED_ORIGINS: string;
}

interface Track {
  videoId: string;
  title: string;
  artist: string;
  thumbnailUrl?: string;
  durationMs?: number;
  fromAutoplay: boolean;
}

interface Member {
  memberId: string;
  userId: string;
  deviceId: string;
  displayName: string;
  avatarUrl?: string;
  token: string;
  isHost: boolean;
  connected: boolean;
  joinedAtMs: number;
  lastSeenMs: number;
  controlBudget: number;
  controlBudgetAtMs: number;
  frameBudget: number;
  frameBudgetAtMs: number;
}

interface Playback {
  track: Track | null;
  queue: Track[];
  queueIndex: number;
  isPlaying: boolean;
  positionMs: number;
  anchorMs: number;
  seq: number;
  queueSeq: number;
  updatedBy: string | null;
  updatedAtMs: number;
  startedBy: string | null;
  startedByName: string | null;
  autoplayEnabled: boolean;
}

interface Party {
  code: string;
  createdAtMs: number;
  touchedAtMs: number;
  emptySinceMs: number | null;
  maxMembers: number;
  hostOnlyControl: boolean;
  members: Member[];
  playback: Playback;
}

interface PresenceEntry { platform: "android" | "pc" | "browser"; lastMs: number }

interface JoinRequest {
  userId?: unknown;
  deviceId?: unknown;
  displayName?: unknown;
  avatarUrl?: unknown;
  maxMembers?: unknown;
  autoplayEnabled?: unknown;
}

interface ValidJoin {
  userId: string;
  deviceId: string;
  displayName: string;
  avatarUrl?: string;
  maxMembers?: number;
  autoplayEnabled?: boolean;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    return route(request, env);
  },
};

export class Registry extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/create") return this.create(request);
    if (request.method === "GET" && url.pathname === "/counts") return this.counts();
    if (request.method === "POST" && url.pathname === "/presence") return this.presence(request);
    if (request.method === "GET" && url.pathname === "/live") return this.liveStats();
    return jsonError(404, "not_found", "Not found.");
  }

  private async create(request: Request): Promise<Response> {
    const body = await readJson<JoinRequest>(request);
    if (body instanceof Response) return body;
    const valid = validateJoin(body, true);
    if (valid instanceof Response) return valid;

    const now = Date.now();
    const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
    const limits = (await this.ctx.storage.get<Record<string, number[]>>("createLimits")) ?? {};
    const recent = (limits[ip] ?? []).filter((time) => now - time < 60_000);
    if (recent.length >= 2) return jsonError(429, "create_rate_limited", "You can create up to two parties per minute. Please try again shortly.");
    if (!(ip in limits) && Object.keys(limits).length >= 10_000) return jsonError(429, "create_rate_limited", "Party creation is temporarily rate limited.");
    limits[ip] = [...recent, now];
    for (const [key, times] of Object.entries(limits)) if (times.every((time) => now - time >= 60_000)) delete limits[key];
    await this.ctx.storage.put("createLimits", limits);

    const rooms = (await this.ctx.storage.get<string[]>("rooms")) ?? [];
    const active: string[] = [];
    for (const code of rooms) {
      const room = this.env.ROOMS.get(this.env.ROOMS.idFromName(code));
      const meta = await room.fetch("https://room.internal/meta");
      if (meta.ok && (await meta.json<{ exists: boolean }>()).exists) active.push(code);
    }
    await this.ctx.storage.put("rooms", active);
    if (active.length >= ROOM_LIMIT) return jsonError(503, "server_busy", "The server is busy. Try again soon.");

    let code = "";
    for (let tries = 0; tries < 12; tries++) {
      code = randomCode();
      if (!active.includes(code)) {
        const meta = await this.env.ROOMS.get(this.env.ROOMS.idFromName(code)).fetch("https://room.internal/meta");
        if ((await meta.json<{ exists: boolean }>()).exists === false) break;
      }
      code = "";
    }
    if (!code) return jsonError(503, "server_busy", "Could not allocate a party code. Try again.");

    const result = await this.env.ROOMS.get(this.env.ROOMS.idFromName(code)).fetch("https://room.internal/create", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...valid, code }),
    });
    if (!result.ok) return result;
    active.push(code);
    await this.ctx.storage.put("rooms", active);
    return result;
  }

  private async counts(): Promise<Response> {
    const rooms = (await this.ctx.storage.get<string[]>("rooms")) ?? [];
    let members = 0;
    let parties = 0;
    const active: string[] = [];
    for (const code of rooms) {
      const response = await this.env.ROOMS.get(this.env.ROOMS.idFromName(code)).fetch("https://room.internal/meta");
      const meta = await response.json<{ exists: boolean; members: number }>();
      if (meta.exists) { active.push(code); parties++; members += meta.members; }
    }
    await this.ctx.storage.put("rooms", active);
    return json({ members, parties, serverMs: Date.now() });
  }

  private async presence(request: Request): Promise<Response> {
    const body = await readJson<{ id?: unknown; platform?: unknown; open?: unknown }>(request);
    if (body instanceof Response) return body;
    if (typeof body.id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.id)) {
      return jsonError(422, "invalid_id", "id must be a UUID.");
    }
    if (body.platform !== "android" && body.platform !== "pc" && body.platform !== "browser") {
      return jsonError(422, "invalid_platform", "platform must be android, pc or browser.");
    }
    if (typeof body.open !== "boolean") return jsonError(422, "invalid_open", "open must be true or false.");
    const origin = request.headers.get("Origin");
    if ((body.platform === "browser") !== Boolean(origin) || (origin && !allowedOrigin(origin, this.env.ALLOWED_ORIGINS))) {
      return jsonError(403, "origin_not_allowed", "Presence is only accepted from the apps and the web player.");
    }
    const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
    const limits = (await this.ctx.storage.get<Record<string, { at: number; count: number }>>("presenceLimits")) ?? {};
    const now = Date.now();
    const limit = limits[ip] ?? { at: now, count: 0 };
    if (now - limit.at >= 60_000) { limit.at = now; limit.count = 0; }
    if (limit.count >= 20) return jsonError(429, "rate_limited", "Too many presence pings from this address.");
    limit.count++;
    limits[ip] = limit;
    for (const [key, value] of Object.entries(limits)) if (now - value.at > 60_000) delete limits[key];
    await this.ctx.storage.put("presenceLimits", limits);

    const entries = (await this.ctx.storage.get<Record<string, PresenceEntry>>("presence")) ?? {};
    if (body.open) {
      if (!(body.id in entries) && Object.keys(entries).length >= 200_000) return json({ intervalSec: 300 });
      entries[body.id] = { platform: body.platform, lastMs: now };
    } else delete entries[body.id];
    for (const [key, value] of Object.entries(entries)) if (now - value.lastMs > 390_000) delete entries[key];
    await this.ctx.storage.put("presence", entries);
    return json({ intervalSec: 300 });
  }

  private async liveStats(): Promise<Response> {
    const entries = (await this.ctx.storage.get<Record<string, PresenceEntry>>("presence")) ?? {};
    const counts = { online: 0, android: 0, pc: 0, browser: 0, updatedAt: Date.now() };
    const cutoff = Date.now() - 390_000;
    for (const [id, entry] of Object.entries(entries)) {
      if (entry.lastMs < cutoff) { delete entries[id]; continue; }
      counts.online++;
      counts[entry.platform]++;
    }
    await this.ctx.storage.put("presence", entries);
    return json(counts, 200, { "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=15" });
  }
}

export class PartyRoom extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/meta" && request.method === "GET") {
      const party = await this.load();
      return json({ exists: !!party && !isExpired(party), members: party?.members.length ?? 0 });
    }
    if (url.pathname === "/create" && request.method === "POST") return this.create(request);
    const party = await this.load();
    if (!party) return jsonError(404, "no_such_party", "No party with that code.");
    if (await this.expireIfNeeded(party)) return jsonError(404, "no_such_party", "No party with that code.");
    if (await this.pruneMembers(party)) {
      this.broadcast(party, membersFrame(party));
      await this.save(party);
    }

    const path = url.pathname;
    if (path === "/preview" && request.method === "GET") return json(previewWire(party));
    if (path === "/join" && request.method === "POST") return this.join(request, party);
    if (path === "/" && request.method === "GET") {
      const member = await this.auth(request, party);
      return member ? json(partyWire(party)) : unauthorized();
    }
    if (path === "/leave" && request.method === "POST") return this.leave(request, party);
    if (path === "/ws" && request.method === "GET") return this.connectPartySocket(request, party);
    return jsonError(404, "not_found", "Not found.");
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    if ((typeof raw === "string" ? new TextEncoder().encode(raw).byteLength : raw.byteLength) > MAX_BODY_BYTES) {
      safeSend(ws, { type: "error", error: "frame_too_large", message: "Message is too large." });
      return;
    }
    let frame: Record<string, unknown>;
    try { frame = JSON.parse(typeof raw === "string" ? raw : new TextDecoder().decode(raw)) as Record<string, unknown>; }
    catch { safeSend(ws, { type: "error", error: "invalid_json", message: "Malformed JSON frame." }); return; }
    const attachment = ws.deserializeAttachment() as { memberId?: string } | null;
    const party = await this.load();
    const member = party?.members.find((item) => item.memberId === attachment?.memberId);
    if (!party || !member) { safeSend(ws, { type: "bye", reason: "not_member" }); ws.close(1008, "not a party member"); return; }
    const now = Date.now();
    if (!spendBudget(member, "frame", now)) { safeSend(ws, { type: "error", error: "rate_limited", message: "Too many messages at once." }); await this.save(party); return; }
    member.lastSeenMs = now;
    const kind = frame.type;
    if (kind === "ping") {
      safeSend(ws, { type: "pong", clientMs: frame.clientMs ?? null, serverMs: now });
      await this.save(party);
      return;
    }
    if (kind === "sync") { safeSend(ws, stateFrame(party)); await this.save(party); return; }
    if (kind === "syncQueue") { safeSend(ws, queueFrame(party)); await this.save(party); return; }
    if (kind !== "control") { await this.save(party); return; }
    if (!spendBudget(member, "control", now)) { safeSend(ws, { type: "error", error: "rate_limited", message: "Too many controls at once." }); await this.save(party); return; }

    const queueSeq = party.playback.queueSeq;
    const result = applyControl(party, member, frame);
    if (!result.ok) {
      if (result.error) safeSend(ws, { type: "error", error: result.error, message: result.message });
      await this.save(party);
      return;
    }
    party.touchedAtMs = now;
    if (party.playback.queueSeq !== queueSeq) this.broadcast(party, queueFrame(party));
    this.broadcast(party, stateFrame(party));
    if (["kick", "setMaxMembers", "setHostOnlyControl"].includes(String(frame.action))) this.broadcast(party, membersFrame(party));
    this.broadcast(party, activityFrame(member, String(frame.action), frame));
    const kickedMemberId = (party as Party & { kickedMemberId?: string }).kickedMemberId;
    if (kickedMemberId) {
      delete (party as Party & { kickedMemberId?: string }).kickedMemberId;
      this.sendMember(kickedMemberId, { type: "bye", reason: "kicked", message: "The host removed you from this party." });
      this.closeMember(kickedMemberId, "kicked");
    }
    await this.save(party);
  }

  async webSocketClose(ws: WebSocket): Promise<void> { await this.markSocketClosed(ws); }
  async webSocketError(ws: WebSocket): Promise<void> { await this.markSocketClosed(ws); }

  async alarm(): Promise<void> {
    const party = await this.load();
    if (!party) return;
    await this.pruneMembers(party);
    if (await this.expireIfNeeded(party)) return;
    const sockets = this.ctx.getWebSockets();
    for (const ws of sockets) {
      const attachment = ws.deserializeAttachment() as { memberId?: string } | null;
      const member = party.members.find((item) => item.memberId === attachment?.memberId);
      if (!member || Date.now() - member.lastSeenMs > MAX_SOCKET_AGE_MS) {
        try { ws.close(1001, "inactive"); } catch { /* closed socket */ }
      }
    }
    if (this.ctx.getWebSockets().length > 0) this.broadcast(party, stateFrame(party));
    await this.save(party);
  }

  private async load(): Promise<Party | undefined> {
    return this.ctx.storage.get<Party>("party");
  }

  private async create(request: Request): Promise<Response> {
    const existing = await this.load();
    if (existing) return jsonError(409, "code_taken", "That party code is already in use.");
    const body = await readJson<ValidJoin & { code: string }>(request);
    if (body instanceof Response) return body;
    const valid = validateJoin(body, true);
    if (valid instanceof Response) return valid;
    const code = normalizeCode(body.code);
    if (!isValidCode(code)) return jsonError(422, "invalid_code", "Invalid party code.");
    const now = Date.now();
    const party: Party = {
      code,
      createdAtMs: now,
      touchedAtMs: now,
      emptySinceMs: now,
      maxMembers: valid.maxMembers ?? 5,
      hostOnlyControl: false,
      members: [],
      playback: emptyPlayback(now, valid.autoplayEnabled ?? false),
    };
    let member: Member;
    try { member = join(party, valid); }
    catch (error) { if (error instanceof Response) return error; throw error; }
    await this.save(party);
    return json({ code, token: member.token, you: memberWire(member), party: partyWire(party) }, 201);
  }

  private async join(request: Request, party: Party): Promise<Response> {
    const body = await readJson<JoinRequest>(request);
    if (body instanceof Response) return body;
    const valid = validateJoin(body, false);
    if (valid instanceof Response) return valid;
    let member: Member;
    try { member = join(party, valid); }
    catch (error) { if (error instanceof Response) return error; throw error; }
    party.touchedAtMs = Date.now();
    await this.save(party);
    return json({ code: party.code, token: member.token, you: memberWire(member), party: partyWire(party) });
  }

  private async leave(request: Request, party: Party): Promise<Response> {
    const member = await this.auth(request, party);
    if (!member) return unauthorized();
    const id = member.memberId;
    removeMember(party, id);
    this.sendMember(id, { type: "bye", reason: "left" });
    this.closeMember(id, "left");
    this.broadcast(party, membersFrame(party), id);
    await this.save(party);
    return json({ ok: true });
  }

  private async connectPartySocket(request: Request, party: Party): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("WebSocket upgrade required.", { status: 426 });
    const offered = (request.headers.get("Sec-WebSocket-Protocol") ?? "").split(",").map((part) => part.trim()).filter(Boolean);
    const token = bearer(request) || offered.find((part) => part.startsWith("vyral.token."))?.slice("vyral.token.".length) || "";
    const member = await findMemberByToken(party, token);
    if (!member) return new Response("bad token", { status: 401 });
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server, [member.memberId]);
    server.serializeAttachment({ memberId: member.memberId });
    const now = Date.now();
    member.connected = true;
    member.lastSeenMs = now;
    party.emptySinceMs = null;
    party.touchedAtMs = now;
    safeSend(server, { type: "welcome", you: memberWire(member), party: partyWire(party), serverMs: now });
    this.broadcast(party, membersFrame(party));
    await this.save(party);
    const headers = new Headers();
    if (offered.includes("vyral.v1")) headers.set("Sec-WebSocket-Protocol", "vyral.v1");
    return new Response(null, { status: 101, webSocket: client, headers });
  }

  private async auth(request: Request, party: Party): Promise<Member | undefined> {
    return findMemberByToken(party, bearer(request));
  }

  private broadcast(party: Party, frame: unknown, exceptMember?: string): void {
    const message = JSON.stringify(frame);
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = ws.deserializeAttachment() as { memberId?: string } | null;
      if (attachment?.memberId === exceptMember) continue;
      try { ws.send(message); } catch { /* close callback handles stale sockets */ }
    }
  }

  private sendMember(memberId: string, frame: unknown): void {
    const message = JSON.stringify(frame);
    for (const ws of this.ctx.getWebSockets(memberId)) safeSend(ws, frame, message);
  }

  private closeMember(memberId: string, reason: string): void {
    for (const ws of this.ctx.getWebSockets(memberId)) {
      try { ws.close(1000, reason); } catch { /* already closed */ }
    }
  }

  private async markSocketClosed(ws: WebSocket): Promise<void> {
    const attachment = ws.deserializeAttachment() as { memberId?: string } | null;
    const party = await this.load();
    if (!party || !attachment?.memberId) return;
    const remaining = this.ctx.getWebSockets(attachment.memberId).filter((item) => item !== ws);
    const member = party.members.find((item) => item.memberId === attachment.memberId);
    if (member && remaining.length === 0) { member.connected = false; member.lastSeenMs = Date.now(); }
    updateEmptyTime(party);
    this.broadcast(party, membersFrame(party));
    await this.save(party);
  }

  private async pruneMembers(party: Party): Promise<boolean> {
    const now = Date.now();
    let changed = false;
    for (const member of [...party.members]) {
      if (!member.connected && now - member.lastSeenMs > MEMBER_GRACE_MS) { removeMember(party, member.memberId); changed = true; }
    }
    return changed;
  }

  private async expireIfNeeded(party: Party): Promise<boolean> {
    if (!isExpired(party)) return false;
    for (const ws of this.ctx.getWebSockets()) { try { ws.close(1001, "party expired"); } catch { /* closed */ } }
    await this.ctx.storage.delete("party");
    await this.ctx.storage.deleteAlarm();
    return true;
  }

  private async save(party: Party): Promise<void> {
    updateEmptyTime(party);
    await this.ctx.storage.put("party", party);
    const now = Date.now();
    const socketMembers = new Set(this.ctx.getWebSockets().map((ws) => (ws.deserializeAttachment() as { memberId?: string } | null)?.memberId));
    let next = party.createdAtMs + PARTY_MAX_AGE_MS;
    if (party.emptySinceMs !== null) next = Math.min(next, party.emptySinceMs + EMPTY_TTL_MS);
    for (const member of party.members) if (!member.connected) next = Math.min(next, member.lastSeenMs + MEMBER_GRACE_MS + 5);
    if (socketMembers.size > 0) next = Math.min(next, now + HEARTBEAT_MS);
    if (next <= now) next = now + 1000;
    await this.ctx.storage.setAlarm(next);
  }
}

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const origin = request.headers.get("Origin");
  if (origin && !allowedOrigin(origin, env.ALLOWED_ORIGINS)) return jsonError(403, "origin_not_allowed", "Origin is not allowed.");
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(origin) });
  if (url.pathname === "/" && request.method === "GET") {
    const response = await registryRequest(env, "GET", "/counts");
    const counts = await response.json<{ members: number; parties: number; serverMs: number }>();
    return withCors(json({ service: "vyral-listen-together", ...counts }), origin);
  }
  if (url.pathname === "/healthz" && request.method === "GET") return withCors(json({ ok: true, serverMs: Date.now() }), origin);
  if (url.pathname === "/api/time" && request.method === "GET") return withCors(json({ serverMs: Date.now() }), origin);
  if (url.pathname === "/api/parties" && request.method === "POST") return withCors(await registryRequest(env, "POST", "/create", request), origin);
  if (url.pathname === "/api/presence" && request.method === "POST") return withCors(await registryRequest(env, "POST", "/presence", request), origin);
  if (url.pathname === "/api/stats/live" && request.method === "GET") return withCors(await registryRequest(env, "GET", "/live"), origin);
  if (url.pathname === "/api/stats/live/badge.svg" && request.method === "GET") {
    const statsResponse = await registryRequest(env, "GET", "/live");
    const stats = await statsResponse.json<{ online: number }>();
    const count = String(stats.online);
    const valueWidth = 8.25 * count.length + 24;
    const width = 124.25 + valueWidth;
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="28" role="img" aria-label="LISTENING NOW: ${count}"><title>LISTENING NOW: ${count}</title><g shape-rendering="crispEdges"><rect width="124.25" height="28" fill="#0d1117"/><rect x="124.25" width="${valueWidth}" height="28" fill="#fb4f67"/></g><g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="100"><text transform="scale(.1)" x="621.25" y="175" textLength="1002.5">LISTENING NOW</text><text transform="scale(.1)" x="${(124.25 + valueWidth / 2) * 10}" y="175" textLength="${82.5 * count.length}" font-weight="bold">${count}</text></g></svg>`;
    return new Response(svg, { headers: { ...corsHeaders(origin), "Access-Control-Allow-Origin": "*", "Content-Type": "image/svg+xml;charset=utf-8", "Cache-Control": "no-cache, no-store, must-revalidate, max-age=0" } });
  }
  if (url.pathname.startsWith("/api/parties/")) {
    const match = url.pathname.match(/^\/api\/parties\/([^/]+)(?:\/(join|preview|leave))?$/);
    if (!match) return withCors(jsonError(404, "not_found", "Not found."), origin);
    const code = normalizeCode(decodeURIComponent(match[1]));
    if (!isValidCode(code)) return withCors(jsonError(404, "no_such_party", "No party with that code."), origin);
    const suffix = match[2] ?? "";
    const method = suffix === "join" || suffix === "leave" ? request.method : request.method;
    const path = suffix ? `/${suffix}` : "/";
    const roomReq = new Request(`https://room.internal${path}`, request);
    return withCors(await env.ROOMS.get(env.ROOMS.idFromName(code)).fetch(roomReq), origin);
  }
  if (url.pathname.startsWith("/ws/parties/")) {
    const match = url.pathname.match(/^\/ws\/parties\/([^/]+)$/);
    if (!match) return new Response("not found", { status: 404 });
    const code = normalizeCode(decodeURIComponent(match[1]));
    if (!isValidCode(code)) return new Response("no such party", { status: 404 });
    const roomReq = new Request(`https://room.internal/ws${url.search}`, request);
    return env.ROOMS.get(env.ROOMS.idFromName(code)).fetch(roomReq);
  }
  if (url.pathname.startsWith("/invite/")) {
    const code = normalizeCode(decodeURIComponent(url.pathname.slice("/invite/".length)));
    if (!isValidCode(code)) return new Response("Not found", { status: 404 });
    const response = await env.ROOMS.get(env.ROOMS.idFromName(code)).fetch("https://room.internal/preview");
    if (!response.ok) return new Response("This VYRAL party is no longer available.", { status: 404, headers: corsHeaders(origin) });
    const preview = await response.json<{ hostName: string; memberCount: number; maxMembers: number; members: Array<{ displayName: string }> }>();
    const names = preview.members.map((member) => escapeHtml(member.displayName)).join(", ");
    const link = `vyral://party/${encodeURIComponent(code)}?server=${encodeURIComponent(url.origin)}`;
    const page = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0b0b0e"><title>Join VYRAL party ${escapeHtml(code)}</title><style>body{margin:0;min-height:100svh;display:grid;place-items:center;background:#0b0b0e;color:#f3f3f7;font:16px system-ui;padding:20px;box-sizing:border-box}.card{max-width:400px;width:100%;padding:30px;border:1px solid #ffffff1a;border-radius:24px;background:#17171d;text-align:center}a{display:block;margin-top:22px;padding:14px;border-radius:14px;background:#8a63ff;color:white;text-decoration:none;font-weight:650}.muted{color:#aaaab5}</style><main class="card"><p class="muted">VYRAL · LISTEN TOGETHER</p><h1>Party ${escapeHtml(code)}</h1><p>${escapeHtml(preview.hostName)} is listening with ${preview.memberCount} of ${preview.maxMembers} devices.</p><p class="muted">${names}</p><a href="${link}">Open VYRAL</a></main></html>`;
    return new Response(page, { headers: { ...corsHeaders(origin), "Content-Type": "text/html;charset=utf-8", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer" } });
  }
  return withCors(jsonError(404, "not_found", "Not found."), origin);
}

async function registryRequest(env: Env, method: string, path: string, source?: Request): Promise<Response> {
  const headers = new Headers(source?.headers);
  const body = source && method === "POST" ? source.body : undefined;
  return env.REGISTRY.get(env.REGISTRY.idFromName("global")).fetch(new Request(`https://registry.internal${path}`, { method, headers, body }));
}

async function readJson<T>(request: Request): Promise<T | Response> {
  const length = Number(request.headers.get("Content-Length") ?? 0);
  if (length > MAX_BODY_BYTES) return jsonError(413, "request_too_large", "Request body is too large.");
  if (!request.body) return jsonError(422, "invalid_json", "Malformed JSON body.");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) { await reader.cancel(); return jsonError(413, "request_too_large", "Request body is too large."); }
      chunks.push(value);
    }
  } catch { return jsonError(422, "invalid_json", "Malformed JSON body."); }
  try {
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    return JSON.parse(text) as T;
  } catch { return jsonError(422, "invalid_json", "Malformed JSON body."); }
}

function validateJoin(raw: JoinRequest, creating: boolean): ValidJoin | Response {
  const userId = typeof raw.userId === "string" ? raw.userId.trim() : "";
  const deviceId = typeof raw.deviceId === "string" ? raw.deviceId.trim() : "";
  const displayName = typeof raw.displayName === "string" ? raw.displayName.trim() : "";
  if (!userId || userId.length > 128) return jsonError(422, "validation_error", "userId must be between 1 and 128 characters");
  if (!deviceId || deviceId.length > 128) return jsonError(422, "validation_error", "deviceId must be between 1 and 128 characters");
  if (!displayName || displayName.length > 80) return jsonError(422, "validation_error", "displayName must be between 1 and 80 characters");
  let avatarUrl: string | undefined;
  if (raw.avatarUrl != null) {
    const value = typeof raw.avatarUrl === "string" ? raw.avatarUrl.trim() : "";
    if (value) {
      if (value.length > 1000) return jsonError(422, "validation_error", "avatarUrl must not exceed 1000 characters");
      try { const url = new URL(value); if (url.protocol !== "https:" && url.protocol !== "http:") throw new Error(); avatarUrl = value; }
      catch { return jsonError(422, "validation_error", "avatarUrl must be an http(s) URL"); }
    }
  }
  const result: ValidJoin = { userId, deviceId, displayName, ...(avatarUrl ? { avatarUrl } : {}) };
  if (creating) {
    const capacity = raw.maxMembers == null ? 5 : raw.maxMembers;
    if (typeof capacity !== "number" || !Number.isInteger(capacity) || capacity < 2 || capacity > 10) return jsonError(422, "validation_error", "maxMembers must be between 2 and 10");
    result.maxMembers = capacity;
    if (raw.autoplayEnabled != null && typeof raw.autoplayEnabled !== "boolean") return jsonError(422, "validation_error", "autoplayEnabled must be true or false");
    if (typeof raw.autoplayEnabled === "boolean") result.autoplayEnabled = raw.autoplayEnabled;
  }
  return result;
}

function join(party: Party, identity: ValidJoin): Member {
  const now = Date.now();
  let member = party.members.find((item) => item.deviceId === identity.deviceId);
  if (member) {
    member.userId = identity.userId;
    member.displayName = identity.displayName;
    member.avatarUrl = identity.avatarUrl;
    member.lastSeenMs = now;
    member.token = randomHex(24);
    party.touchedAtMs = now;
    return member;
  }
  if (party.members.length >= party.maxMembers) throw new Response(JSON.stringify({ error: "party_full", message: `This party is full (${party.maxMembers} devices).` }), { status: 409, headers: { "Content-Type": "application/json" } });
  member = {
    memberId: randomHex(8), userId: identity.userId, deviceId: identity.deviceId, displayName: identity.displayName,
    ...(identity.avatarUrl ? { avatarUrl: identity.avatarUrl } : {}), token: randomHex(24),
    isHost: party.members.length === 0, connected: false, joinedAtMs: now, lastSeenMs: now,
    controlBudget: 25, controlBudgetAtMs: now, frameBudget: 30, frameBudgetAtMs: now,
  };
  party.members.push(member);
  party.touchedAtMs = now;
  return member;
}

function emptyPlayback(now: number, autoplayEnabled: boolean): Playback {
  return { track: null, queue: [], queueIndex: -1, isPlaying: false, positionMs: 0, anchorMs: now, seq: 0, queueSeq: 0, updatedBy: null, updatedAtMs: now, startedBy: null, startedByName: null, autoplayEnabled };
}

function partyWire(party: Party): Record<string, unknown> {
  const now = Date.now();
  return {
    code: party.code, createdAtMs: party.createdAtMs, maxMembers: party.maxMembers, hostOnlyControl: party.hostOnlyControl,
    members: [...party.members].sort((a, b) => a.joinedAtMs - b.joinedAtMs).map(memberWire),
    playback: playbackWire(party.playback, now), queue: queueWire(party.playback), serverMs: now,
  };
}

function memberWire(member: Member): Record<string, unknown> {
  return { memberId: member.memberId, userId: member.userId, displayName: member.displayName, avatarUrl: member.avatarUrl ?? null, isHost: member.isHost, connected: member.connected, joinedAtMs: member.joinedAtMs, lastSeenMs: member.lastSeenMs };
}

function playbackWire(playback: Playback, now: number): Record<string, unknown> {
  return { seq: playback.seq, track: playback.track, queueSeq: playback.queueSeq, queueIndex: playback.queueIndex, queueLength: playback.queue.length, isPlaying: playback.isPlaying, positionMs: playback.positionMs, anchorMs: playback.anchorMs, effectivePositionMs: positionAt(playback, now), updatedBy: playback.updatedBy, startedBy: playback.startedBy, startedByName: playback.startedByName, autoplayEnabled: playback.autoplayEnabled, updatedAtMs: playback.updatedAtMs };
}

function queueWire(playback: Playback): Record<string, unknown> { return { seq: playback.queueSeq, index: playback.queueIndex, items: playback.queue }; }
function stateFrame(party: Party): Record<string, unknown> { const now = Date.now(); return { type: "state", playback: playbackWire(party.playback, now), serverMs: now }; }
function queueFrame(party: Party): Record<string, unknown> { return { type: "queue", queue: queueWire(party.playback), serverMs: Date.now() }; }
function membersFrame(party: Party): Record<string, unknown> { return { type: "members", members: party.members.map(memberWire), maxMembers: party.maxMembers, hostOnlyControl: party.hostOnlyControl, serverMs: Date.now() }; }
function activityFrame(member: Member, action: string, frame: Record<string, unknown>): Record<string, unknown> {
  let detail = action;
  const title = (raw: unknown): string => raw && typeof raw === "object" && typeof (raw as Record<string, unknown>).title === "string" ? String((raw as Record<string, unknown>).title) : "";
  switch (action) {
    case "setTrack": { const value = title(frame.track); if (value) detail = `Changed the song to \"${value}\"`; break; }
    case "queueAdd": { const tracks = Array.isArray(frame.tracks) ? frame.tracks : frame.track ? [frame.track] : []; const value = title(tracks[0]); if (value) detail = tracks.length === 1 ? `Added \"${value}\" to the queue` : `Added \"${value}\" and ${tracks.length - 1} more to the queue`; break; }
    case "setQueue": detail = `Replaced the queue with ${Array.isArray(frame.queue) ? frame.queue.length : 0} tracks`; break;
    case "queueRemove": detail = "Removed a track from the queue"; break;
    case "queueClear": detail = "Cleared upcoming tracks"; break;
    case "queueMove": detail = "Reordered the upcoming queue"; break;
    case "next": detail = "Skipped to the next song"; break;
    case "previous": detail = "Went back to the previous song"; break;
    case "play": detail = "Started playback"; break;
    case "pause": detail = "Paused playback"; break;
    case "seek": detail = "Changed the playback position"; break;
    case "kick": detail = "Removed a listener from the party"; break;
    case "setMaxMembers": detail = "Changed the party size"; break;
    case "setAutoplay": detail = "Changed AutoPlay"; break;
  }
  return { type: "activity", action, by: member.displayName, detail, atMs: Date.now() };
}
function previewWire(party: Party): Record<string, unknown> {
  const members = [...party.members].sort((a, b) => a.joinedAtMs - b.joinedAtMs);
  return { code: party.code, hostName: members.find((member) => member.isHost)?.displayName ?? "", memberCount: members.length, maxMembers: party.maxMembers, isFull: members.length >= party.maxMembers, members: members.map((member) => ({ displayName: member.displayName, avatarUrl: member.avatarUrl ?? null, isHost: member.isHost })) };
}

function applyControl(party: Party, member: Member, frame: Record<string, unknown>): { ok: boolean; error?: string; message?: string } {
  const action = typeof frame.action === "string" ? frame.action : "";
  const restricted = new Set(["play", "pause", "seek", "setTrack", "setQueue", "queueAdd", "queueRemove", "queueClear", "queueMove", "next", "previous", "setAutoplay"]);
  if (restricted.has(action) && party.hostOnlyControl && !member.isHost) return fail("host_only", "Only the host can control the music in this party.");
  const now = Date.now();
  const position = finiteNumber(frame.positionMs);
  const playback = party.playback;
  const trackFrom = (raw: unknown): Track | null => {
    if (!raw || typeof raw !== "object") return null;
    const item = raw as Record<string, unknown>;
    if (typeof item.videoId !== "string" || !item.videoId) return null;
    const track: Track = { videoId: item.videoId.slice(0, 128), title: typeof item.title === "string" ? item.title.slice(0, 300) : "", artist: typeof item.artist === "string" ? item.artist.slice(0, 300) : "", fromAutoplay: item.fromAutoplay === true };
    if (typeof item.thumbnailUrl === "string" && item.thumbnailUrl) track.thumbnailUrl = item.thumbnailUrl.slice(0, 1000);
    if (typeof item.durationMs === "number" && Number.isFinite(item.durationMs) && item.durationMs > 0) track.durationMs = Math.trunc(item.durationMs);
    return track;
  };
  const touch = () => { playback.seq++; playback.updatedBy = member.memberId; playback.updatedAtMs = Date.now(); };
  const touchQueue = () => { playback.queueSeq++; playback.updatedBy = member.memberId; playback.updatedAtMs = Date.now(); };
  const setTrack = (track: Track, pos: number, isPlaying: boolean, requestedIndex?: number) => {
    playback.track = track;
    playback.positionMs = Math.max(0, pos);
    playback.isPlaying = isPlaying;
    playback.anchorMs = now + (isPlaying ? PLAY_LEAD_MS : 0);
    if (requestedIndex != null && requestedIndex >= 0 && requestedIndex < playback.queue.length && playback.queue[requestedIndex]?.videoId === track.videoId) playback.queueIndex = requestedIndex;
    else playback.queueIndex = nearestIndex(playback.queue, track.videoId, playback.queueIndex);
    playback.startedBy = member.memberId;
    playback.startedByName = member.displayName;
    touch();
  };
  switch (action) {
    case "play": {
      playback.positionMs = position == null ? positionAt(playback, now) : Math.max(0, position);
      playback.isPlaying = true; playback.anchorMs = now + PLAY_LEAD_MS; touch(); return { ok: true };
    }
    case "pause": {
      playback.positionMs = position == null ? positionAt(playback, now) : Math.max(0, position);
      playback.isPlaying = false; playback.anchorMs = now; touch(); return { ok: true };
    }
    case "seek":
      if (position == null) return fail("missing_position", "seek requires positionMs");
      playback.positionMs = Math.max(0, position); playback.anchorMs = now + (playback.isPlaying ? PLAY_LEAD_MS : 0); touch(); return { ok: true };
    case "setTrack": {
      const track = trackFrom(frame.track);
      if (!track) return fail("invalid_track", "Track must include videoId");
      setTrack(track, position ?? 0, frame.isPlaying !== false, finiteNumber(frame.queueIndex)); return { ok: true };
    }
    case "setQueue": {
      const queue = parseTracks(frame.queue, trackFrom);
      let index = finiteNumber(frame.queueIndex) ?? -1;
      if (playback.track && (index < 0 || index >= queue.length || queue[index]?.videoId !== playback.track.videoId)) index = nearestIndex(queue, playback.track.videoId, index);
      if (index >= 0 && index < queue.length) {
        const limited = queue.slice(0, Math.min(queue.length, index + 1 + MAX_UPCOMING));
        playback.queue = limited; playback.queueIndex = index;
      } else {
        playback.queue = queue.slice(0, MAX_QUEUE);
        playback.queueIndex = index >= 0 && index < playback.queue.length ? index : -1;
      }
      touchQueue(); return { ok: true };
    }
    case "queueAdd": {
      let tracks = frame.track ? [trackFrom(frame.track)].filter((item): item is Track => item !== null) : parseTracks(frame.tracks, trackFrom);
      const waiting = new Set(playback.queue.slice(playback.queueIndex + 1).map((item) => item.videoId));
      tracks = tracks.filter((track) => {
        if (!track.fromAutoplay) return true;
        if (waiting.has(track.videoId)) return false;
        waiting.add(track.videoId); return true;
      });
      const upcoming = playback.queueIndex >= 0 ? Math.max(0, playback.queue.length - playback.queueIndex - 1) : playback.queue.length;
      const slots = MAX_UPCOMING - upcoming;
      if (tracks.length === 0) return frame.track || Array.isArray(frame.tracks) ? { ok: true } : fail("no_tracks", "Could not add track to queue.");
      if (slots <= 0) return fail("queue_full", `Queue is full (maximum ${MAX_UPCOMING} upcoming songs).`);
      tracks = tracks.slice(0, slots);
      if (frame.playNext === true && playback.queueIndex >= 0 && playback.queueIndex < playback.queue.length) playback.queue.splice(playback.queueIndex + 1, 0, ...tracks);
      else playback.queue.push(...tracks);
      touchQueue(); return { ok: true };
    }
    case "queueRemove": {
      let id = typeof frame.videoId === "string" ? frame.videoId : "";
      if (!id && finiteNumber(frame.index) != null) id = playback.queue[finiteNumber(frame.index)!]?.videoId ?? "";
      if (!id) return fail("missing_track", "queueRemove requires videoId or index");
      const index = playback.queue.findIndex((item) => item.videoId === id);
      if (index < 0 || index === playback.queueIndex) return fail("not_found", "Track is not in the upcoming queue");
      playback.queue.splice(index, 1); if (playback.queueIndex > index) playback.queueIndex--; touchQueue(); return { ok: true };
    }
    case "queueClear":
      if (playback.queueIndex >= 0) { if (playback.queue.length <= playback.queueIndex + 1) return fail("no_upcoming", "No upcoming tracks to clear"); playback.queue = playback.queue.slice(0, playback.queueIndex + 1); }
      else { if (!playback.queue.length) return fail("no_upcoming", "No upcoming tracks to clear"); playback.queue = []; }
      touchQueue(); return { ok: true };
    case "queueMove": {
      const from = finiteNumber(frame.fromIndex); const to = finiteNumber(frame.toIndex);
      if (from == null || to == null) return fail("missing_indices", "queueMove requires fromIndex and toIndex");
      const actual = typeof frame.videoId === "string" && frame.videoId ? playback.queue.findIndex((item) => item.videoId === frame.videoId) : from;
      if (actual < 0 || actual >= playback.queue.length || to < 0 || to >= playback.queue.length || actual <= playback.queueIndex || to <= playback.queueIndex) return fail("invalid_move", "Invalid queue move indices");
      if (actual === to) return { ok: true };
      const [item] = playback.queue.splice(actual, 1); playback.queue.splice(to, 0, item); touchQueue(); return { ok: true };
    }
    case "next": case "previous": {
      const target = playback.queueIndex + (action === "next" ? 1 : -1);
      if (target < 0 || target >= playback.queue.length) return fail("end_of_queue", "There are no more tracks in that direction.");
      setTrack(playback.queue[target], 0, true, target); return { ok: true };
    }
    case "setMaxMembers": {
      if (!member.isHost) return fail("host_only", "Only the host can change the party size.");
      const value = finiteNumber(frame.maxMembers);
      if (value == null || value < 2 || value > 10 || !Number.isInteger(value)) return fail("invalid_capacity", "Choose a party size between 2 and 10.");
      if (value < party.members.length) return fail("party_too_small", "Party size cannot be smaller than the current member count.");
      party.maxMembers = value; party.touchedAtMs = Date.now(); return { ok: true };
    }
    case "setAutoplay":
      if (typeof frame.enabled !== "boolean") return fail("invalid_autoplay", "AutoPlay must be enabled or disabled.");
      if (playback.autoplayEnabled !== frame.enabled) { playback.autoplayEnabled = frame.enabled; playback.updatedBy = member.memberId; playback.updatedAtMs = Date.now(); }
      return { ok: true };
    case "setHostOnlyControl":
      if (!member.isHost) return fail("host_only", "Only the host can change who controls the music.");
      if (typeof frame.enabled !== "boolean") return fail("invalid_control_policy", "Host-only control must be enabled or disabled.");
      party.hostOnlyControl = frame.enabled; party.touchedAtMs = Date.now(); return { ok: true };
    case "kick": {
      if (!member.isHost) return fail("host_only", "Only the host can remove listeners.");
      const target = typeof frame.memberId === "string" ? frame.memberId : "";
      if (!target || target === member.memberId) return fail("invalid_member", "Choose another listener to remove.");
      if (!party.members.some((item) => item.memberId === target)) return fail("not_found", "That listener is no longer in this party.");
      // The caller sends the bye and closes the target sockets after applyControl.
      (party as Party & { kickedMemberId?: string }).kickedMemberId = target;
      removeMember(party, target); return { ok: true };
    }
    default: return fail("unknown_action", `Unknown control action '${action}'`);
  }
}

function parseTracks(raw: unknown, fromWire: (item: unknown) => Track | null): Track[] {
  if (!Array.isArray(raw)) return [];
  return raw.map(fromWire).filter((item): item is Track => item !== null);
}

function nearestIndex(queue: Track[], videoId: string, around: number): number {
  if (around + 1 >= 0 && around + 1 < queue.length && queue[around + 1]?.videoId === videoId) return around + 1;
  if (around - 1 >= 0 && around - 1 < queue.length && queue[around - 1]?.videoId === videoId) return around - 1;
  let best = -1; let distanceBest = 0;
  queue.forEach((item, index) => {
    if (item.videoId !== videoId) return;
    const delta = index - around;
    const distance = delta < 0 ? -delta * 2 + 1 : delta;
    if (best < 0 || distance < distanceBest) { best = index; distanceBest = distance; }
  });
  return best;
}

function positionAt(playback: Playback, now: number): number {
  if (!playback.isPlaying) return playback.positionMs;
  let position = playback.positionMs + Math.max(0, now - playback.anchorMs);
  if (playback.track?.durationMs && playback.track.durationMs > 0) position = Math.min(position, playback.track.durationMs);
  return position;
}

function spendBudget(member: Member, type: "control" | "frame", now: number): boolean {
  const rate = type === "control" ? 25 : 30;
  const budgetKey = type === "control" ? "controlBudget" : "frameBudget";
  const atKey = type === "control" ? "controlBudgetAtMs" : "frameBudgetAtMs";
  const elapsed = Math.max(0, now - member[atKey]) / 1000;
  member[budgetKey] = Math.min(rate, member[budgetKey] + elapsed * rate);
  member[atKey] = now;
  if (member[budgetKey] < 1) return false;
  member[budgetKey]--;
  return true;
}

function removeMember(party: Party, memberId: string): Member | undefined {
  const index = party.members.findIndex((member) => member.memberId === memberId);
  if (index < 0) return undefined;
  const [member] = party.members.splice(index, 1);
  if (member.isHost && party.members.length > 0) party.members[0]!.isHost = true;
  party.touchedAtMs = Date.now();
  updateEmptyTime(party);
  return member;
}

function updateEmptyTime(party: Party): void {
  if (party.members.some((member) => member.connected)) party.emptySinceMs = null;
  else if (party.emptySinceMs === null) party.emptySinceMs = Date.now();
}

function isExpired(party: Party): boolean {
  const now = Date.now();
  return now - party.createdAtMs > PARTY_MAX_AGE_MS || (party.emptySinceMs !== null && now - party.emptySinceMs > EMPTY_TTL_MS);
}

async function findMemberByToken(party: Party, token: string): Promise<Member | undefined> {
  if (!token) return undefined;
  const candidate = await digest(token);
  for (const member of party.members) {
    const expected = await digest(member.token);
    if (constantTimeEqual(candidate, expected)) return member;
  }
  return undefined;
}

async function digest(value: string): Promise<Uint8Array> { return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))); }
function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
  let diff = a.length ^ b.length;
  const length = Math.max(a.length, b.length);
  for (let i = 0; i < length; i++) diff |= (a[i % a.length] ?? 0) ^ (b[i % b.length] ?? 0);
  return diff === 0;
}

function randomHex(bytes: number): string {
  const value = crypto.getRandomValues(new Uint8Array(bytes));
  return [...value].map((part) => part.toString(16).padStart(2, "0")).join("");
}
function randomCode(): string {
  const values = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  return [...values].map((value) => CODE_ALPHABET[value % CODE_ALPHABET.length]).join("");
}
function normalizeCode(raw: string): string {
  return raw.trim().toUpperCase().replace(/[^A-Z0-9]/g, "").replace(/[IL]/g, "1").replace(/O/g, "0");
}
function isValidCode(code: string): boolean { return code.length === CODE_LENGTH && [...code].every((char) => CODE_ALPHABET.includes(char)); }
function bearer(request: Request): string {
  const [scheme, token] = (request.headers.get("Authorization") ?? "").split(/\s+/, 2);
  return scheme?.toLowerCase() === "bearer" ? (token ?? "").trim() : "";
}
function finiteNumber(value: unknown): number | undefined { return typeof value === "number" && Number.isFinite(value) ? value : undefined; }
function fail(error: string, message: string): { ok: false; error: string; message: string } { return { ok: false, error, message }; }
function json(value: unknown, status = 200, headers?: HeadersInit): Response {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json", ...Object.fromEntries(new Headers(headers).entries()) } });
}
function jsonError(status: number, error: string, message: string): Response { return json({ error, message }, status); }
function unauthorized(): Response { return jsonError(401, "unauthorized", "Missing or invalid bearer token."); }
function safeSend(ws: WebSocket, frame: unknown, serialized = JSON.stringify(frame)): void { try { ws.send(serialized); } catch { /* socket closed */ } }
function corsHeaders(origin: string | null): Headers {
  const headers = new Headers();
  if (origin) { headers.set("Access-Control-Allow-Origin", origin); headers.set("Vary", "Origin"); }
  headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
  return headers;
}
function withCors(response: Response, origin: string | null): Response {
  const headers = new Headers(response.headers);
  const cors = corsHeaders(origin);
  cors.forEach((value, key) => headers.set(key, value));
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers, ...(response as Response & { webSocket?: WebSocket }).webSocket ? { webSocket: (response as Response & { webSocket?: WebSocket }).webSocket } : {} });
}
function allowedOrigin(origin: string, raw: string): boolean { return raw.split(",").map((item) => item.trim()).filter(Boolean).includes(origin); }
function escapeHtml(value: string): string { return value.replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" })[char]!); }
