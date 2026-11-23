import { Router } from "express";
import { z } from "zod";
import { q } from "./db.js";
import { emit } from "./hub.js";
import { ApiError, auth } from "./security.js";
import { PCOLS, areFriends, blockedEither, notFound, pair, person, uuid } from "./common.js";
import { saveFile, upload } from "./files.js";
import { notify } from "./account.js";

export const messagesRouter = Router();

async function hydrate(rows: any[]) {
  if (!rows.length) return [];
  const ids = rows.map((r) => r.id);
  const at = await q("SELECT id,message_id,kind,mime,size,original_name,duration_ms FROM attachments WHERE message_id=ANY($1::uuid[])", [ids]);
  const rx = await q("SELECT message_id,emoji,array_agg(user_id) AS users FROM message_reactions WHERE message_id=ANY($1::uuid[]) GROUP BY message_id,emoji ORDER BY min(created_at)", [ids]);
  return rows.map((r) => ({
    id: r.id, conversationId: r.conversation_id, senderId: r.sender_id, body: r.deleted_at ? null : r.body, replyTo: r.reply_to,
    createdAt: r.created_at, editedAt: r.edited_at, deleted: !!r.deleted_at, pinned: !!r.pinned_at && !r.deleted_at, gifUrl: r.deleted_at ? null : r.gif_url,
    attachments: r.deleted_at ? [] : at.filter((a) => a.message_id === r.id).map((a) => ({ id: a.id, kind: a.kind, mime: a.mime, name: a.original_name, size: Number(a.size), durationMs: a.duration_ms })),
    reactions: rx.filter((x) => x.message_id === r.id).map((x) => ({ emoji: x.emoji, users: x.users })),
  }));
}
const one = async (id: string) => (await hydrate(await q("SELECT * FROM messages WHERE id=$1", [id])))[0];

async function otherMember(convId: string, me: string): Promise<string> {
  const [r] = await q(`SELECT o.user_id FROM conversation_members m JOIN conversation_members o ON o.conversation_id=m.conversation_id AND o.user_id<>m.user_id
                       WHERE m.conversation_id=$1 AND m.user_id=$2`, [convId, me]);
  if (!r) throw new ApiError(404, "NOT_FOUND", "Conversation not found.");
  return r.user_id;
}
async function memberMessage(req: any) {
  const me = auth(req).userId;
  const [m] = await q("SELECT * FROM messages WHERE id=$1 AND deleted_at IS NULL", [uuid.parse(req.params.id)]);
  if (!m) throw notFound();
  return { me, m, other: await otherMember(m.conversation_id, me) }; // throws 404 if I'm not in that conversation
}
const both = (a: string, b: string, type: string, data: unknown) => { emit(a, type, data); emit(b, type, data); };

messagesRouter.get("/conversations", async (req, res) => {
  const me = auth(req).userId;
  const rows = await q(
    `SELECT c.id AS conv_id, me.muted, ${PCOLS},
       lm.id AS last_id, lm.sender_id AS last_sender, lm.body AS last_body, lm.deleted_at AS last_deleted, lm.created_at AS last_at, lm.gif_url AS last_gif, lm.att_kind,
       (SELECT count(*) FROM messages m2 WHERE m2.conversation_id=c.id AND m2.sender_id<>$1 AND m2.deleted_at IS NULL AND m2.created_at>me.last_read_at AND m2.created_at>me.cleared_at)::int AS unread
     FROM conversation_members me
     JOIN conversations c ON c.id=me.conversation_id
     JOIN conversation_members om ON om.conversation_id=c.id AND om.user_id<>$1
     JOIN users u ON u.id=om.user_id
     LEFT JOIN LATERAL (SELECT id,sender_id,body,deleted_at,created_at,gif_url,(SELECT kind FROM attachments a WHERE a.message_id=messages.id LIMIT 1) AS att_kind
                        FROM messages WHERE conversation_id=c.id AND created_at>me.cleared_at ORDER BY created_at DESC LIMIT 1) lm ON true
     WHERE me.user_id=$1 AND NOT me.hidden AND u.deleted_at IS NULL
     ORDER BY COALESCE(lm.created_at, c.created_at) DESC`, [me]);
  res.json(rows.map((r) => ({ id: r.conv_id, user: person(r), unread: r.unread, muted: r.muted,
    last: r.last_id ? { id: r.last_id, senderId: r.last_sender, body: r.last_deleted ? null : r.last_body, createdAt: r.last_at, deleted: !!r.last_deleted,
      kind: r.last_deleted ? null : r.last_gif ? "gif" : r.att_kind } : null })));
});

messagesRouter.post("/conversations", async (req, res) => {
  const me = auth(req).userId; const { userId } = z.object({ userId: uuid }).parse(req.body);
  if (!(await areFriends(me, userId)) || (await blockedEither(me, userId))) throw new ApiError(403, "NOT_ALLOWED", "You can only message your friends.");
  const [l, h] = pair(me, userId);
  const [c] = await q("INSERT INTO conversations(dm_key) VALUES($1) ON CONFLICT (dm_key) DO UPDATE SET dm_key=EXCLUDED.dm_key RETURNING id", [`${l}:${h}`]);
  await q("INSERT INTO conversation_members(conversation_id,user_id) VALUES($1,$2),($1,$3) ON CONFLICT DO NOTHING", [c.id, me, userId]);
  await q("UPDATE conversation_members SET hidden=false WHERE conversation_id=$1 AND user_id=$2", [c.id, me]);
  res.json({ id: c.id });
});

messagesRouter.get("/conversations/:id/messages", async (req, res) => {
  const me = auth(req).userId, id = uuid.parse(req.params.id); const other = await otherMember(id, me);
  const before = z.string().datetime().optional().parse(req.query.before);
  const rows = await q(`SELECT m.* FROM messages m JOIN conversation_members mem ON mem.conversation_id=m.conversation_id AND mem.user_id=$2
     WHERE m.conversation_id=$1 AND m.created_at>mem.cleared_at AND ($3::timestamptz IS NULL OR m.created_at<$3::timestamptz) ORDER BY m.created_at DESC LIMIT 50`, [id, me, before ?? null]);
  const [o] = await q("SELECT last_read_at FROM conversation_members WHERE conversation_id=$1 AND user_id=$2", [id, other]);
  res.json({ messages: await hydrate(rows), otherLastReadAt: o?.last_read_at ?? null });
});
messagesRouter.get("/conversations/:id/pins", async (req, res) => {
  const id = uuid.parse(req.params.id); await otherMember(id, auth(req).userId);
  res.json(await hydrate(await q("SELECT * FROM messages WHERE conversation_id=$1 AND pinned_at IS NOT NULL AND deleted_at IS NULL ORDER BY pinned_at DESC LIMIT 50", [id])));
});
messagesRouter.get("/conversations/:id/search", async (req, res) => {
  const me = auth(req).userId, id = uuid.parse(req.params.id); await otherMember(id, me);
  const term = z.string().trim().min(1).max(100).parse(req.query.q).replace(/[\\%_]/g, (c) => "\\" + c);
  res.json(await hydrate(await q(`SELECT m.* FROM messages m JOIN conversation_members mem ON mem.conversation_id=m.conversation_id AND mem.user_id=$2
     WHERE m.conversation_id=$1 AND m.deleted_at IS NULL AND m.created_at>mem.cleared_at AND m.body ILIKE $3 ESCAPE '\\' ORDER BY m.created_at DESC LIMIT 50`, [id, me, `%${term}%`])));
});

async function createMessage(me: string, convId: string, o: { body?: string; replyTo?: string | null; gifUrl?: string | null; attachmentId?: string }) {
  const other = await otherMember(convId, me);
  if (!(await areFriends(me, other)) || (await blockedEither(me, other))) throw new ApiError(403, "NOT_ALLOWED", "You can't message this user.");
  if (o.replyTo && !(await q("SELECT 1 FROM messages WHERE id=$1 AND conversation_id=$2", [o.replyTo, convId])).length) throw new ApiError(400, "BAD_REPLY", "Reply target not found.");
  const [m] = await q("INSERT INTO messages(conversation_id,sender_id,body,reply_to,gif_url) VALUES($1,$2,$3,$4,$5) RETURNING *", [convId, me, o.body ?? "", o.replyTo ?? null, o.gifUrl ?? null]);
  if (o.attachmentId) await q("UPDATE attachments SET message_id=$1 WHERE id=$2 AND uploader_id=$3", [m.id, o.attachmentId, me]);
  await q("UPDATE conversation_members SET hidden=false WHERE conversation_id=$1", [convId]);
  const out = (await hydrate([m]))[0]; both(me, other, "message.new", out);
  // replies and @mentions create persisted notifications (respecting the recipient's settings)
  const [oth] = await q("SELECT username FROM users WHERE id=$1", [other]);
  if (o.replyTo) { const [t] = await q("SELECT sender_id FROM messages WHERE id=$1", [o.replyTo]); if (t?.sender_id === other) void notify(other, "reply", me, { conversationId: convId, preview: (o.body ?? "").slice(0, 80) }, "mentions"); }
  else if (o.body && new RegExp(`@${oth.username}\\b`, "i").test(o.body)) void notify(other, "mention", me, { conversationId: convId, preview: o.body.slice(0, 80) }, "mentions");
  return out;
}

messagesRouter.post("/conversations/:id/messages", async (req, res) => {
  const me = auth(req).userId, id = uuid.parse(req.params.id);
  const b = z.object({ body: z.string().trim().max(4000).optional(), replyTo: uuid.nullish(),
    gifUrl: z.string().url().max(500).refine((u) => { try { const h = new URL(u); return h.protocol === "https:" && /(^|\.)(tenor\.com|giphy\.com)$/.test(h.hostname); } catch { return false; } }, "Unsupported GIF source.").nullish() }).parse(req.body);
  if (!b.body && !b.gifUrl) throw new ApiError(400, "EMPTY", "Message can't be empty.");
  res.status(201).json(await createMessage(me, id, b));
});

messagesRouter.post("/conversations/:id/attachments", upload.single("file"), async (req, res) => {
  const me = auth(req).userId, id = uuid.parse(req.params.id); await otherMember(id, me);
  const f = z.object({ kind: z.enum(["image", "file", "voice"]), body: z.string().trim().max(4000).optional(), replyTo: uuid.optional(), durationMs: z.coerce.number().int().min(0).max(3_600_000).optional() }).parse(req.body);
  const attId = await saveFile(me, req.file, { kind: f.kind, conversationId: id, durationMs: f.durationMs });
  res.status(201).json(await createMessage(me, id, { body: f.body, replyTo: f.replyTo, attachmentId: attId }));
});

messagesRouter.get("/gifs/search", async (req, res) => {
  const key = process.env.TENOR_API_KEY;
  if (!key) throw new ApiError(503, "GIF_NOT_CONFIGURED", "GIF search isn't configured on this server (set TENOR_API_KEY).");
  const term = z.string().trim().max(60).parse(req.query.q ?? "");
  const url = `https://tenor.googleapis.com/v2/${term ? "search" : "featured"}?q=${encodeURIComponent(term)}&key=${key}&client_key=sumx&limit=24&media_filter=tinygif,gif&contentfilter=medium`;
  const r = await fetch(url); if (!r.ok) throw new ApiError(502, "GIF_FAILED", "GIF search failed.");
  const j: any = await r.json();
  res.json((j.results ?? []).map((x: any) => ({ id: x.id, previewUrl: x.media_formats?.tinygif?.url, url: x.media_formats?.gif?.url })).filter((x: any) => x.previewUrl && x.url));
});

messagesRouter.patch("/messages/:id", async (req, res) => {
  const { me, m, other } = await memberMessage(req);
  if (m.sender_id !== me) throw notFound();
  const b = z.object({ body: z.string().trim().min(1).max(4000) }).parse(req.body);
  await q("UPDATE messages SET body=$1, edited_at=now() WHERE id=$2", [b.body, m.id]);
  const out = await one(m.id); both(me, other, "message.edit", out); res.json(out);
});
messagesRouter.delete("/messages/:id", async (req, res) => {
  const { me, m, other } = await memberMessage(req);
  if (m.sender_id !== me) throw notFound();
  await q("UPDATE messages SET deleted_at=now() WHERE id=$1", [m.id]);
  const out = await one(m.id); both(me, other, "message.delete", out); res.json(out);
});
messagesRouter.post("/messages/:id/reactions", async (req, res) => {
  const { me, m, other } = await memberMessage(req);
  const { emoji } = z.object({ emoji: z.string().min(1).max(16).regex(/^\p{Extended_Pictographic}/u, "Pick an emoji.") }).parse(req.body);
  const del = await q("DELETE FROM message_reactions WHERE message_id=$1 AND user_id=$2 AND emoji=$3 RETURNING 1", [m.id, me, emoji]);
  if (!del.length) await q("INSERT INTO message_reactions(message_id,user_id,emoji) VALUES($1,$2,$3)", [m.id, me, emoji]);
  const out = await one(m.id); both(me, other, "message.reaction", out); res.json(out);
});
messagesRouter.post("/messages/:id/pin", async (req, res) => {
  const { me, m, other } = await memberMessage(req);
  await q("UPDATE messages SET pinned_at = CASE WHEN pinned_at IS NULL THEN now() ELSE NULL END WHERE id=$1", [m.id]);
  const out = await one(m.id); both(me, other, "message.pin", out); res.json(out);
});

messagesRouter.post("/conversations/:id/read", async (req, res) => {
  const me = auth(req).userId, id = uuid.parse(req.params.id); const other = await otherMember(id, me);
  const [r] = await q("UPDATE conversation_members SET last_read_at=now() WHERE conversation_id=$1 AND user_id=$2 RETURNING last_read_at", [id, me]);
  emit(other, "message.read", { conversationId: id, userId: me, at: r.last_read_at }); res.json({ ok: true });
});
messagesRouter.post("/conversations/:id/mute", async (req, res) => {
  const { muted } = z.object({ muted: z.boolean() }).parse(req.body); const id = uuid.parse(req.params.id); await otherMember(id, auth(req).userId);
  await q("UPDATE conversation_members SET muted=$1 WHERE conversation_id=$2 AND user_id=$3", [muted, id, auth(req).userId]); res.json({ ok: true });
});
messagesRouter.post("/conversations/:id/hide", async (req, res) => {
  const id = uuid.parse(req.params.id); await otherMember(id, auth(req).userId);
  await q("UPDATE conversation_members SET hidden=true, cleared_at=now(), last_read_at=now() WHERE conversation_id=$1 AND user_id=$2", [id, auth(req).userId]); res.json({ ok: true });
});
