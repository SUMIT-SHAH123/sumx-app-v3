import { Router } from "express";
import { z } from "zod";
import argon2 from "argon2";
import { pool, q } from "./db.js";
import { emit } from "./hub.js";
import { ApiError, auth, sha256 } from "./security.js";
import { PCOLS, broadcastPresence, friendIds, person, uuid } from "./common.js";
import { verifyGoogle } from "./auth.js";
import { deleteFile } from "./files.js";

const b = (d: boolean) => z.boolean().default(d);
export const settingsSchema = z.object({
  status: z.enum(["online", "idle", "dnd", "invisible"]).default("online"),
  activityVisibility: z.enum(["friends", "nobody"]).default("friends"),
  listeningVisibility: z.enum(["friends", "nobody"]).default("friends"),
  listeningShow: z.object({ title: b(true), artist: b(true), artwork: b(true), platform: b(true), duration: b(true) }).default({}),
  notify: z.object({ messages: b(true), friendRequests: b(true), calls: b(true), mentions: b(true), activity: b(false) }).default({}),
  accessibility: z.object({ reduceMotion: b(false), largeText: b(false) }).default({}),
  voice: z.object({ noiseSuppression: b(true), echoCancellation: b(true), startVideoOff: b(false) }).default({}),
});
export type Settings = z.infer<typeof settingsSchema>;
export async function getSettings(userId: string): Promise<Settings> {
  const [r] = await q("SELECT data FROM user_settings WHERE user_id=$1", [userId]);
  return settingsSchema.parse(r?.data ?? {});
}

/** Persist + push a notification, honoring the recipient's notification settings. */
export async function notify(userId: string, type: string, actorId: string | null, data: any = {}, pref?: keyof Settings["notify"]) {
  const s = await getSettings(userId);
  if (pref && !s.notify[pref]) return;
  const [n] = await q("INSERT INTO notifications(user_id,actor_id,type,data) VALUES($1,$2,$3,$4) RETURNING id,created_at", [userId, actorId, type, data]);
  emit(userId, "notification", { id: n.id, type, actorId, data, createdAt: n.created_at, silent: s.status === "dnd" });
}

export const accountRouter = Router();

accountRouter.get("/settings", async (req, res) => res.json(await getSettings(auth(req).userId)));
accountRouter.put("/settings", async (req, res) => {
  const me = auth(req).userId, s = settingsSchema.parse(req.body), prev = await getSettings(me);
  await q("INSERT INTO user_settings(user_id,data) VALUES($1,$2) ON CONFLICT (user_id) DO UPDATE SET data=EXCLUDED.data", [me, s]);
  if (prev.status !== s.status) void broadcastPresence(me);
  res.json(s);
});

accountRouter.get("/notifications", async (req, res) => {
  const me = auth(req).userId;
  const rows = await q(`SELECT n.id AS nid, n.type, n.data, n.read_at, n.created_at AS n_created, ${PCOLS} FROM notifications n LEFT JOIN users u ON u.id=n.actor_id
                        WHERE n.user_id=$1 ORDER BY n.created_at DESC LIMIT 50`, [me]);
  const [c] = await q("SELECT count(*)::int AS c FROM notifications WHERE user_id=$1 AND read_at IS NULL", [me]);
  res.json({ unread: c.c, items: rows.map((r) => ({ id: r.nid, type: r.type, data: r.data, read: !!r.read_at, createdAt: r.n_created, actor: r.id ? person(r) : null })) });
});
accountRouter.post("/notifications/read-all", async (req, res) => {
  await q("UPDATE notifications SET read_at=now() WHERE user_id=$1 AND read_at IS NULL", [auth(req).userId]); res.json({ ok: true });
});
accountRouter.post("/notifications/:id/read", async (req, res) => {
  await q("UPDATE notifications SET read_at=now() WHERE id=$1 AND user_id=$2", [uuid.parse(req.params.id), auth(req).userId]); res.json({ ok: true });
});

accountRouter.get("/sessions", async (req, res) => {
  const rows = await q("SELECT id,device,ip,created_at,last_used_at FROM sessions WHERE user_id=$1 AND revoked_at IS NULL AND expires_at>now() ORDER BY last_used_at DESC", [auth(req).userId]);
  res.json(rows.map((r) => ({ id: r.id, device: r.device, ip: r.ip, createdAt: r.created_at, lastUsedAt: r.last_used_at, current: r.id === auth(req).sessionId })));
});
accountRouter.delete("/sessions/:id", async (req, res) => {
  await q("UPDATE sessions SET revoked_at=now() WHERE id=$1 AND user_id=$2", [uuid.parse(req.params.id), auth(req).userId]); res.json({ ok: true });
});

accountRouter.post("/account/username", async (req, res) => {
  const me = auth(req).userId;
  const body = z.object({ username: z.string().trim().toLowerCase().regex(/^[a-z0-9_]{3,20}$/, "Username must be 3-20 letters, numbers or underscores.") }).parse(req.body);
  const [u] = await q("SELECT username,sumx_id,username_changed_at FROM users WHERE id=$1", [me]);
  if (u.username === body.username) throw new ApiError(400, "SAME", "That's already your username.");
  if (u.username_changed_at && Date.now() - new Date(u.username_changed_at).getTime() < 14 * 864e5)
    throw new ApiError(429, "TOO_SOON", "You can change your username once every 14 days.");
  let digits = u.sumx_id.slice(-4);
  for (let i = 0; i < 6; i++) {
    try {
      const [r] = await q("UPDATE users SET username=$1, sumx_id=$2, username_changed_at=now() WHERE id=$3 RETURNING sumx_id", [body.username, `@${body.username}${digits}`, me]);
      for (const f of await friendIds(me)) emit(f, "friend.update", {});
      return void res.json({ username: body.username, sumxId: r.sumx_id });
    } catch (e: any) {
      if (e.code !== "23505") throw e;
      if (String(e.constraint).includes("username")) throw new ApiError(409, "USERNAME_TAKEN", "That username is taken.");
      digits = String(1000 + Math.floor(Math.random() * 9000)); // ID collision: pick new digits
    }
  }
  throw new ApiError(500, "SERVER", "Could not update username.");
});

accountRouter.delete("/account", async (req, res) => {
  const me = auth(req).userId;
  const body = z.object({ password: z.string().max(128).optional(), idToken: z.string().optional() }).parse(req.body);
  const [u] = await q("SELECT password_hash FROM users WHERE id=$1", [me]);
  if (u.password_hash) {
    if (!body.password || !(await argon2.verify(u.password_hash, body.password))) throw new ApiError(403, "BAD_PASSWORD", "Incorrect password.");
  } else {
    if (!body.idToken) throw new ApiError(403, "REAUTH", "Confirm with Google to delete this account.");
    const g = await verifyGoogle(body.idToken);
    if (!(await q("SELECT 1 FROM linked_accounts WHERE user_id=$1 AND provider='google' AND provider_user_id=$2", [me, g.sub])).length)
      throw new ApiError(403, "REAUTH", "That Google account doesn't match.");
  }
  const friends = await friendIds(me);
  const files = await q("SELECT id FROM attachments WHERE uploader_id=$1 AND kind IN ('avatar','banner','background')", [me]);
  const c = await pool.connect();
  try {
    await c.query("BEGIN");
    await c.query(`UPDATE users SET deleted_at=now(), email=format('deleted-%s@deleted.invalid',id), username=left('deleted_'||replace(id::text,'-',''),20),
                   sumx_id='@d_'||replace(id::text,'-',''), display_name='Deleted User', password_hash=NULL, email_verified=false, date_of_birth=NULL WHERE id=$1`, [me]);
    await c.query("UPDATE profiles SET avatar_url=NULL,banner_url=NULL,background_url=NULL,about='',pronouns=NULL,custom_status=NULL WHERE user_id=$1", [me]);
    for (const t of ["friendships WHERE user_low=$1 OR user_high=$1", "friend_requests WHERE from_user=$1 OR to_user=$1", "blocks WHERE blocker_id=$1 OR blocked_id=$1",
      "linked_accounts WHERE user_id=$1", "notifications WHERE user_id=$1", "activities WHERE user_id=$1", "listening_activities WHERE user_id=$1", "user_settings WHERE user_id=$1"])
      await c.query(`DELETE FROM ${t}`, [me]);
    await c.query("UPDATE sessions SET revoked_at=now() WHERE user_id=$1", [me]);
    await c.query("COMMIT");
  } catch (e) { await c.query("ROLLBACK"); throw e; } finally { c.release(); }
  for (const f of files) await deleteFile(f.id);
  for (const f of friends) emit(f, "friend.update", {});
  res.json({ ok: true });
});
void sha256;
