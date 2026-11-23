import crypto from "node:crypto";
import { Router } from "express";
import { q } from "./db.js";
import { emit, isOnline } from "./hub.js";
import { ApiError, auth } from "./security.js";
import { PCOLS, areFriends, blockedEither, person, uuid } from "./common.js";
import { notify } from "./account.js";

type Call = { id: string; caller: string; callee: string; kind: string; answered: boolean; timer?: NodeJS.Timeout };
const calls = new Map<string, Call>();
const inCall = new Map<string, string>(); // userId -> callId

const err = (u: string, message: string) => emit(u, "call.error", { message });

async function finish(c: Call, status: "ended" | "declined" | "missed" | "cancelled") {
  if (!calls.delete(c.id)) return;
  clearTimeout(c.timer); inCall.delete(c.caller); inCall.delete(c.callee);
  await q("UPDATE calls SET status=$1, ended_at=now() WHERE id=$2", [status, c.id]);
  emit(c.caller, "call.ended", { callId: c.id, reason: status }); emit(c.callee, "call.ended", { callId: c.id, reason: status });
  if (status === "missed") void notify(c.callee, "missed_call", c.caller, { kind: c.kind }, "calls");
}

export async function handleCallMessage(me: string, m: any) {
  try {
    if (m.type === "call.invite") {
      const callee = uuid.parse(m.calleeId); const kind = m.kind === "video" ? "video" : "voice";
      if (inCall.has(me)) return err(me, "You're already in a call.");
      if (callee === me || !(await areFriends(me, callee)) || (await blockedEither(me, callee))) return err(me, "You can only call your friends.");
      const [row] = await q("INSERT INTO calls(caller_id,callee_id,kind) VALUES($1,$2,$3) RETURNING id", [me, callee, kind]);
      const c: Call = { id: row.id, caller: me, callee, kind, answered: false };
      if (!isOnline(callee)) { await q("UPDATE calls SET status='missed', ended_at=now() WHERE id=$1", [c.id]); void notify(callee, "missed_call", me, { kind }, "calls"); return err(me, "They're offline right now."); }
      if (inCall.has(callee)) { await q("UPDATE calls SET status='missed', ended_at=now() WHERE id=$1", [c.id]); return err(me, "They're on another call."); }
      calls.set(c.id, c); inCall.set(me, c.id); inCall.set(callee, c.id);
      c.timer = setTimeout(() => void finish(c, "missed"), 45_000);
      const [u] = await q(`SELECT ${PCOLS} FROM users u WHERE u.id=$1`, [me]);
      emit(callee, "call.incoming", { callId: c.id, kind, from: person(u) });
      emit(me, "call.ringing", { callId: c.id, calleeId: callee, kind });
      return;
    }
    const c = calls.get(String(m.callId)); if (!c || (c.caller !== me && c.callee !== me)) return;
    const other = c.caller === me ? c.callee : c.caller;
    if (m.type === "call.accept" && me === c.callee && !c.answered) {
      c.answered = true; clearTimeout(c.timer);
      await q("UPDATE calls SET status='accepted', answered_at=now() WHERE id=$1", [c.id]);
      emit(c.caller, "call.accepted", { callId: c.id });
    } else if (m.type === "call.decline" && me === c.callee && !c.answered) await finish(c, "declined");
    else if (m.type === "call.end") await finish(c, !c.answered && me === c.caller ? "cancelled" : "ended");
    else if (m.type === "call.signal" && c.answered) emit(other, "call.signal", { callId: c.id, payload: m.payload });
  } catch { err(me, "Call failed."); }
}
export function onCallDisconnect(userId: string) { const id = inCall.get(userId); const c = id && calls.get(id); if (c) void finish(c, c.answered ? "ended" : "cancelled"); }

export const callsRouter = Router();
callsRouter.get("/calls/ice-servers", (req, res) => {
  const servers: any[] = [{ urls: (process.env.STUN_URLS ?? "stun:stun.l.google.com:19302").split(",") }];
  if (process.env.TURN_URLS && process.env.TURN_SECRET) { // coturn "static-auth-secret" ephemeral credentials
    const username = `${Math.floor(Date.now() / 1000) + 3600}:${auth(req).userId}`;
    servers.push({ urls: process.env.TURN_URLS.split(","), username, credential: crypto.createHmac("sha1", process.env.TURN_SECRET).update(username).digest("base64") });
  }
  res.json({ iceServers: servers });
});
callsRouter.get("/calls", async (req, res) => {
  const me = auth(req).userId;
  const rows = await q(`SELECT c.id AS call_id,c.kind,c.status AS call_status,c.created_at AS call_created,c.answered_at,c.ended_at,(c.caller_id=$1) AS outgoing, ${PCOLS}
    FROM calls c JOIN users u ON u.id = CASE WHEN c.caller_id=$1 THEN c.callee_id ELSE c.caller_id END
    WHERE (c.caller_id=$1 OR c.callee_id=$1) AND c.status<>'ringing' ORDER BY c.created_at DESC LIMIT 50`, [me]);
  res.json(rows.map((r) => ({ id: r.call_id, kind: r.kind, status: r.call_status, outgoing: r.outgoing, createdAt: r.call_created, user: person(r),
    durationSec: r.answered_at && r.ended_at ? Math.max(0, Math.round((new Date(r.ended_at).getTime() - new Date(r.answered_at).getTime()) / 1000)) : 0 })));
});
void ApiError;
