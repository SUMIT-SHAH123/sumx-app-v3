import { Router } from "express";
import { z } from "zod";
import { q } from "./db.js";
import { emit } from "./hub.js";
import { ApiError, auth } from "./security.js";
import { PCOLS, areFriends, blockedEither, friendIds, notFound, person, uuid } from "./common.js";
import { deleteFile, saveFile, upload } from "./files.js";
import { getSettings, notify, type Settings } from "./account.js";

export const profileRouter = Router();
const hex = z.string().regex(/^#[0-9a-fA-F]{6}$/, "Use a hex color like #006BFF.");
const patchSchema = z.object({
  displayName: z.string().trim().min(1).max(32), about: z.string().max(190), pronouns: z.string().trim().max(24).nullable(),
  customStatus: z.string().trim().max(60).nullable(), bannerColor: hex, accentColor: hex, bannerPosition: z.number().int().min(0).max(100),
  frame: z.enum(["none", "ring", "gradient", "double"]), decoration: z.enum(["none", "star", "crown", "flame", "bolt"]),
  effect: z.enum(["none", "sparkle", "pulse"]), nameStyle: z.enum(["plain", "gradient", "glow", "neon"]),
  theme: z.enum(["midnight", "ocean", "neon", "sunset", "graphite"]),
}).partial();
const COL: Record<string, string> = { about: "about", pronouns: "pronouns", customStatus: "custom_status", bannerColor: "banner_color", accentColor: "accent_color",
  bannerPosition: "banner_position", frame: "frame", decoration: "decoration", effect: "effect", nameStyle: "name_style", theme: "theme" };

async function badges(userId: string) {
  const [r] = await q(`SELECT u.email_verified, u.created_at, EXISTS(SELECT 1 FROM linked_accounts l WHERE l.user_id=u.id) AS linked,
    (SELECT count(*) FROM friendships f WHERE f.user_low=u.id OR f.user_high=u.id)::int AS friends FROM users u WHERE u.id=$1`, [userId]);
  const out: { id: string; label: string }[] = [];
  if (r.email_verified) out.push({ id: "verified", label: "Verified email" });
  if (r.linked) out.push({ id: "connected", label: "Google connected" });
  if (r.friends >= 5) out.push({ id: "social", label: "5+ friends" });
  if (Date.now() - new Date(r.created_at).getTime() > 365 * 864e5) out.push({ id: "veteran", label: "1 year on SUM X" });
  return out;
}

// ---- activity helpers (privacy applied here, server-side) ----
type Act = { current: any; listening: any };
async function bulkActivity(ids: string[], viewerIsSelf: boolean): Promise<Map<string, Act>> {
  const out = new Map<string, Act>(); if (!ids.length) return out;
  const cur = await q("SELECT * FROM activities WHERE user_id=ANY($1::uuid[]) AND updated_at>now()-interval '6 hours'", [ids]);
  const lis = await q("SELECT * FROM listening_activities WHERE user_id=ANY($1::uuid[]) AND updated_at>now()-interval '6 hours'", [ids]);
  const sets = new Map<string, Settings>(); for (const id of ids) sets.set(id, await getSettings(id));
  for (const id of ids) {
    const s = sets.get(id)!; const c = cur.find((x) => x.user_id === id); const l = lis.find((x) => x.user_id === id);
    const show = (flag: boolean) => viewerIsSelf || flag;
    out.set(id, {
      current: c && (viewerIsSelf || s.activityVisibility === "friends") ? { type: c.type, name: c.name, details: c.details, startedAt: c.started_at } : null,
      listening: l && (viewerIsSelf || s.listeningVisibility === "friends") ? {
        platform: show(s.listeningShow.platform) ? l.platform : null, title: show(s.listeningShow.title) ? l.title : null,
        artist: show(s.listeningShow.artist) ? l.artist : null, artworkUrl: show(s.listeningShow.artwork) ? l.artwork_url : null,
        startedAt: show(s.listeningShow.duration) ? l.started_at : null, durationSec: l.duration_sec, source: l.source } : null,
    });
  }
  return out;
}

async function fullProfile(targetId: string, viewerId: string) {
  const self = targetId === viewerId;
  const [u] = await q(`SELECT ${PCOLS}, u.created_at, p.* FROM users u JOIN profiles p ON p.user_id=u.id WHERE u.id=$1 AND u.deleted_at IS NULL`, [targetId]);
  if (!u) throw notFound();
  const friend = self || (await areFriends(targetId, viewerId));
  const pr = person(u);
  const act = friend ? (await bulkActivity([targetId], self)).get(targetId)! : { current: null, listening: null };
  let mutual = 0;
  if (friend && !self) {
    const mine = new Set(await friendIds(viewerId)); mutual = (await friendIds(targetId)).filter((x) => mine.has(x)).length;
  }
  return {
    id: u.id, displayName: u.display_name, sumxId: u.sumx_id, username: u.username, createdAt: u.created_at,
    avatarUrl: u.avatar_url, bannerUrl: u.banner_url, backgroundUrl: u.background_url, bannerColor: u.banner_color, accentColor: u.accent_color,
    bannerPosition: u.banner_position, about: u.about, pronouns: u.pronouns, customStatus: u.custom_status,
    frame: u.frame, decoration: u.decoration, effect: u.effect, nameStyle: u.name_style, theme: u.theme,
    badges: await badges(targetId), isFriend: friend && !self,
    status: friend ? pr.status : null, activity: act.current, listening: act.listening, mutualFriends: mutual,
  };
}

profileRouter.get("/profile/me", async (req, res) => res.json(await fullProfile(auth(req).userId, auth(req).userId)));

profileRouter.patch("/profile", async (req, res) => {
  const me = auth(req).userId; const b = patchSchema.parse(req.body);
  if (b.displayName) await q("UPDATE users SET display_name=$1 WHERE id=$2", [b.displayName, me]);
  const keys = Object.keys(b).filter((k) => COL[k]);
  if (keys.length) await q(`UPDATE profiles SET ${keys.map((k, i) => `${COL[k]}=$${i + 2}`).join(",")}, updated_at=now() WHERE user_id=$1`, [me, ...keys.map((k) => (b as any)[k])]);
  for (const f of await friendIds(me)) emit(f, "friend.update", {});
  res.json(await fullProfile(me, me));
});

const SLOT_COL: Record<string, string> = { avatar: "avatar_url", banner: "banner_url", background: "background_url" };
profileRouter.post("/profile/:slot(avatar|banner|background)", upload.single("file"), async (req, res) => {
  const me = auth(req).userId, slot = req.params.slot;
  const id = await saveFile(me, req.file, { kind: slot });
  const old = await q("SELECT id FROM attachments WHERE uploader_id=$1 AND kind=$2 AND id<>$3", [me, slot, id]);
  await q(`UPDATE profiles SET ${SLOT_COL[slot]}=$1, updated_at=now() WHERE user_id=$2`, [`/files/${id}`, me]);
  for (const o of old) await deleteFile(o.id);
  for (const f of await friendIds(me)) emit(f, "friend.update", {});
  res.json(await fullProfile(me, me));
});
profileRouter.delete("/profile/:slot(avatar|banner|background)", async (req, res) => {
  const me = auth(req).userId, slot = req.params.slot;
  const old = await q("SELECT id FROM attachments WHERE uploader_id=$1 AND kind=$2", [me, slot]);
  await q(`UPDATE profiles SET ${SLOT_COL[slot]}=NULL, updated_at=now() WHERE user_id=$1`, [me]);
  for (const o of old) await deleteFile(o.id);
  res.json(await fullProfile(me, me));
});

profileRouter.get("/users/:id/profile", async (req, res) => {
  const me = auth(req).userId, id = uuid.parse(req.params.id);
  if (id !== me && (await blockedEither(me, id))) throw notFound();
  res.json(await fullProfile(id, me));
});

// ---- activity ----
async function pushActivityUpdate(me: string) { for (const f of await friendIds(me)) emit(f, "activity.update", { userId: me }); }

profileRouter.put("/activity", async (req, res) => {
  const me = auth(req).userId;
  const b = z.object({ type: z.enum(["playing", "watching", "building", "custom", "using_sumx"]), name: z.string().trim().min(1, "Enter what you're doing.").max(60), details: z.string().trim().max(100).nullish() }).parse(req.body);
  await q(`INSERT INTO activities(user_id,type,name,details) VALUES($1,$2,$3,$4)
           ON CONFLICT (user_id) DO UPDATE SET type=$2,name=$3,details=$4,started_at=now(),updated_at=now()`, [me, b.type, b.name, b.details ?? null]);
  void pushActivityUpdate(me);
  const label = { playing: "Playing", watching: "Watching", building: "Building", custom: "", using_sumx: "Using" }[b.type];
  for (const f of await friendIds(me)) void notify(f, "activity", me, { text: `${label} ${b.name}`.trim() }, "activity");
  res.json({ ok: true });
});
profileRouter.delete("/activity", async (req, res) => { await q("DELETE FROM activities WHERE user_id=$1", [auth(req).userId]); void pushActivityUpdate(auth(req).userId); res.json({ ok: true }); });

profileRouter.put("/activity/listening", async (req, res) => {
  const me = auth(req).userId;
  const b = z.object({
    platform: z.enum(["sumx_beats", "spotify", "youtube_music", "local", "other"]), title: z.string().trim().min(1).max(120), artist: z.string().trim().max(120).nullish(),
    artworkUrl: z.string().url().max(500).refine((u) => u.startsWith("https://"), "Artwork must be an https URL").nullish(),
    durationSec: z.number().int().min(0).max(86400).nullish(), source: z.enum(["manual", "device"]).default("manual"),
  }).parse(req.body);
  const [prev] = await q("SELECT title,artist,updated_at FROM listening_activities WHERE user_id=$1", [me]);
  const same = prev && prev.title === b.title && prev.artist === (b.artist ?? null) && Date.now() - new Date(prev.updated_at).getTime() < 6 * 3600e3;
  await q(`INSERT INTO listening_activities(user_id,platform,title,artist,artwork_url,duration_sec,source) VALUES($1,$2,$3,$4,$5,$6,$7)
           ON CONFLICT (user_id) DO UPDATE SET platform=$2,title=$3,artist=$4,artwork_url=$5,duration_sec=$6,source=$7,updated_at=now()
             ${same ? "" : ",started_at=now()"}`, [me, b.platform, b.title, b.artist ?? null, b.artworkUrl ?? null, b.durationSec ?? null, b.source]);
  void pushActivityUpdate(me); res.json({ ok: true });
});
profileRouter.delete("/activity/listening", async (req, res) => { await q("DELETE FROM listening_activities WHERE user_id=$1", [auth(req).userId]); void pushActivityUpdate(auth(req).userId); res.json({ ok: true }); });

profileRouter.get("/activity/feed", async (req, res) => {
  const me = auth(req).userId; const fids = await friendIds(me);
  const people = fids.length ? await q(`SELECT ${PCOLS} FROM users u WHERE u.id=ANY($1::uuid[]) AND u.deleted_at IS NULL`, [fids]) : [];
  const acts = await bulkActivity(fids, false);
  const active = [], recent = [];
  for (const r of people) {
    const p = person(r), a = acts.get(r.id)!;
    if (a.current || a.listening) active.push({ user: p, activity: a.current, listening: a.listening });
    else if (p.status === "offline" && r.last_seen_at && Date.now() - new Date(r.last_seen_at).getTime() < 24 * 3600e3) recent.push(p);
  }
  const mine = (await bulkActivity([me], true)).get(me)!;
  res.json({ mine: { activity: mine.current, listening: mine.listening }, active, recent });
});
void ApiError;
