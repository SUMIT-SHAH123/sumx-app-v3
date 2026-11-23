import { Router } from "express";
import { z } from "zod";
import { q } from "./db.js";
import { emit } from "./hub.js";
import { ApiError, auth } from "./security.js";
import { PCOLS, areFriends, blockedEither, notFound, pair, person, uuid } from "./common.js";
import { notify } from "./account.js";

export const socialRouter = Router();

async function addFriendship(a: string, b: string) {
  const [l, h] = pair(a, b);
  await q("INSERT INTO friendships(user_low,user_high) VALUES($1,$2) ON CONFLICT DO NOTHING", [l, h]);
  emit(a, "friend.update", {}); emit(b, "friend.update", {});
}

// ================= friends =================
socialRouter.get("/friends", async (req, res) => {
  const me = auth(req).userId;
  const rows = await q(
    `SELECT ${PCOLS} FROM friendships f JOIN users u ON u.id = CASE WHEN f.user_low=$1 THEN f.user_high ELSE f.user_low END
     WHERE (f.user_low=$1 OR f.user_high=$1) AND u.deleted_at IS NULL ORDER BY lower(u.display_name)`, [me]);
  res.json(rows.map(person));
});

socialRouter.get("/friends/requests", async (req, res) => {
  const me = auth(req).userId;
  const inc = await q(`SELECT r.id AS rid, ${PCOLS} FROM friend_requests r JOIN users u ON u.id=r.from_user WHERE r.to_user=$1 AND u.deleted_at IS NULL ORDER BY r.created_at DESC`, [me]);
  const out = await q(`SELECT r.id AS rid, ${PCOLS} FROM friend_requests r JOIN users u ON u.id=r.to_user WHERE r.from_user=$1 AND u.deleted_at IS NULL ORDER BY r.created_at DESC`, [me]);
  const map = (r: any) => ({ id: r.rid, user: person(r) });
  res.json({ incoming: inc.map(map), outgoing: out.map(map) });
});

socialRouter.get("/friends/blocked", async (req, res) => {
  const rows = await q(`SELECT ${PCOLS} FROM blocks b JOIN users u ON u.id=b.blocked_id WHERE b.blocker_id=$1 ORDER BY b.created_at DESC`, [auth(req).userId]);
  res.json(rows.map(person));
});

socialRouter.get("/friends/:userId/mutual", async (req, res) => {
  const me = auth(req).userId, other = uuid.parse(req.params.userId);
  if (!(await areFriends(me, other))) throw notFound();
  const side = (p: string) => `SELECT CASE WHEN user_low=${p} THEN user_high ELSE user_low END FROM friendships WHERE user_low=${p} OR user_high=${p}`;
  const rows = await q(`SELECT ${PCOLS} FROM users u WHERE u.id IN (${side("$1")} INTERSECT ${side("$2")}) AND u.deleted_at IS NULL`, [me, other]);
  res.json(rows.map(person));
});

socialRouter.post("/friends/request", async (req, res) => {
  const me = auth(req).userId;
  const b = z.object({ sumxId: z.string().trim().toLowerCase().min(2).max(40) }).parse(req.body);
  const id = b.sumxId.startsWith("@") ? b.sumxId : "@" + b.sumxId;
  const [t] = await q("SELECT id FROM users WHERE sumx_id=$1 AND deleted_at IS NULL", [id]);
  const nope = new ApiError(404, "NOT_FOUND", "No user found with that SUM X ID.");
  if (!t) throw nope;
  if (t.id === me) throw new ApiError(400, "SELF", "You can't add yourself.");
  if (await blockedEither(me, t.id)) throw nope;                      // don't reveal who blocked whom
  if (await areFriends(me, t.id)) throw new ApiError(409, "ALREADY_FRIENDS", "You're already friends.");
  const reverse = await q("DELETE FROM friend_requests WHERE from_user=$1 AND to_user=$2 RETURNING id", [t.id, me]);
  if (reverse.length) { await addFriendship(me, t.id); await notify(t.id, "friend_accepted", me, {}, "friendRequests"); return void res.json({ status: "friends" }); }
  const ins = await q("INSERT INTO friend_requests(from_user,to_user) VALUES($1,$2) ON CONFLICT DO NOTHING RETURNING id", [me, t.id]);
  if (!ins.length) throw new ApiError(409, "ALREADY_SENT", "Friend request already sent.");
  emit(t.id, "friend.request", {}); emit(me, "friend.update", {});
  await notify(t.id, "friend_request", me, {}, "friendRequests");
  res.status(201).json({ status: "sent" });
});

socialRouter.post("/friends/requests/:id/accept", async (req, res) => {
  const me = auth(req).userId;
  const [r] = await q("DELETE FROM friend_requests WHERE id=$1 AND to_user=$2 RETURNING from_user", [uuid.parse(req.params.id), me]);
  if (!r) throw notFound();
  if (await blockedEither(me, r.from_user)) throw notFound();
  await addFriendship(me, r.from_user);
  await notify(r.from_user, "friend_accepted", me, {}, "friendRequests");
  res.json({ ok: true });
});
// reject (recipient) and cancel (sender)
socialRouter.post("/friends/requests/:id/reject", async (req, res) => {
  const me = auth(req).userId;
  const [r] = await q("DELETE FROM friend_requests WHERE id=$1 AND to_user=$2 RETURNING from_user", [uuid.parse(req.params.id), me]);
  if (!r) throw notFound();
  emit(r.from_user, "friend.update", {}); emit(me, "friend.update", {});
  res.json({ ok: true });
});
socialRouter.post("/friends/requests/:id/cancel", async (req, res) => {
  const me = auth(req).userId;
  const [r] = await q("DELETE FROM friend_requests WHERE id=$1 AND from_user=$2 RETURNING to_user", [uuid.parse(req.params.id), me]);
  if (!r) throw notFound();
  emit(r.to_user, "friend.update", {}); emit(me, "friend.update", {});
  res.json({ ok: true });
});

socialRouter.delete("/friends/:userId", async (req, res) => {
  const me = auth(req).userId, other = uuid.parse(req.params.userId);
  const [l, h] = pair(me, other);
  await q("DELETE FROM friendships WHERE user_low=$1 AND user_high=$2", [l, h]);
  emit(other, "friend.update", {}); emit(me, "friend.update", {});
  res.json({ ok: true });
});

socialRouter.post("/blocks/:userId", async (req, res) => {
  const me = auth(req).userId, other = uuid.parse(req.params.userId);
  if (me === other) throw new ApiError(400, "SELF", "You can't block yourself.");
  const [u] = await q("SELECT 1 FROM users WHERE id=$1 AND deleted_at IS NULL", [other]);
  if (!u) throw notFound();
  const [l, h] = pair(me, other);
  await q("DELETE FROM friendships WHERE user_low=$1 AND user_high=$2", [l, h]);
  await q("DELETE FROM friend_requests WHERE (from_user=$1 AND to_user=$2) OR (from_user=$2 AND to_user=$1)", [me, other]);
  await q("INSERT INTO blocks(blocker_id,blocked_id) VALUES($1,$2) ON CONFLICT DO NOTHING", [me, other]);
  emit(other, "friend.update", {}); emit(me, "friend.update", {});
  res.json({ ok: true });
});
socialRouter.delete("/blocks/:userId", async (req, res) => {
  await q("DELETE FROM blocks WHERE blocker_id=$1 AND blocked_id=$2", [auth(req).userId, uuid.parse(req.params.userId)]);
  emit(auth(req).userId, "friend.update", {});
  res.json({ ok: true });
});

