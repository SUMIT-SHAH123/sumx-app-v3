import { z } from "zod";
import { q } from "./db.js";
import { ApiError } from "./security.js";
import { emit, isOnline } from "./hub.js";

export const uuid = z.string().uuid();
export const pair = (a: string, b: string) => (a < b ? [a, b] : [b, a]);
export const notFound = () => new ApiError(404, "NOT_FOUND", "Not found.");
export const PCOLS =
  "u.id,u.display_name,u.sumx_id,u.username,u.last_seen_at," +
  "(SELECT avatar_url FROM profiles p WHERE p.user_id=u.id) AS avatar_url," +
  "(SELECT accent_color FROM profiles p WHERE p.user_id=u.id) AS accent_color," +
  "(SELECT data->>'status' FROM user_settings s WHERE s.user_id=u.id) AS status";

export function person(r: any) {
  const st = r.status ?? "online";
  const status = !isOnline(r.id) || st === "invisible" ? "offline" : st;
  return { id: r.id, displayName: r.display_name, sumxId: r.sumx_id, username: r.username, avatarUrl: r.avatar_url ?? null,
    accentColor: r.accent_color ?? null, status, online: status !== "offline", lastSeen: r.last_seen_at ?? null };
}
export async function blockedEither(a: string, b: string) {
  return (await q("SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)", [a, b])).length > 0;
}
export async function areFriends(a: string, b: string) {
  const [l, h] = pair(a, b);
  return (await q("SELECT 1 FROM friendships WHERE user_low=$1 AND user_high=$2", [l, h])).length > 0;
}
export async function friendIds(id: string): Promise<string[]> {
  const rows = await q("SELECT CASE WHEN user_low=$1 THEN user_high ELSE user_low END AS fid FROM friendships WHERE user_low=$1 OR user_high=$1", [id]);
  return rows.map((r) => r.fid);
}
export async function statusOf(id: string) {
  if (!isOnline(id)) return "offline";
  const [r] = await q("SELECT data->>'status' AS s FROM user_settings WHERE user_id=$1", [id]);
  const s = r?.s ?? "online";
  return s === "invisible" ? "offline" : s;
}
export async function broadcastPresence(id: string) {
  const s = await statusOf(id);
  for (const f of await friendIds(id)) emit(f, "presence", { userId: id, status: s, online: s !== "offline" });
}
