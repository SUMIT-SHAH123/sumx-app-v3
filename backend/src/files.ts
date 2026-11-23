import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import multer from "multer";
import { Router } from "express";
import { q } from "./db.js";
import { ApiError, auth } from "./security.js";
import { blockedEither, notFound, uuid } from "./common.js";

export const UPLOAD_DIR = path.resolve(process.env.UPLOAD_DIR ?? "./uploads");
fs.mkdirSync(UPLOAD_DIR, { recursive: true });
export const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 15 * 1024 * 1024, files: 1 } });

export function sniffImage(b: Buffer): string | null {   // never trust the client's mime type
  if (b.length < 12) return null;
  if (b[0] === 0x89 && b.subarray(1, 4).toString() === "PNG") return "image/png";
  if (b[0] === 0xff && b[1] === 0xd8) return "image/jpeg";
  if (b.subarray(0, 3).toString() === "GIF") return "image/gif";
  if (b.subarray(0, 4).toString() === "RIFF" && b.subarray(8, 12).toString() === "WEBP") return "image/webp";
  return null;
}
const VOICE = new Set(["audio/mp4", "audio/aac", "audio/mpeg", "audio/ogg", "audio/webm", "audio/x-m4a"]);
const PROFILE_KINDS = ["avatar", "banner", "background"];

export async function saveFile(userId: string, file: Express.Multer.File | undefined, o: { kind: string; conversationId?: string; durationMs?: number }) {
  if (!file) throw new ApiError(400, "NO_FILE", "No file was uploaded.");
  let mime = "application/octet-stream", ext = "bin";
  if (o.kind === "image" || PROFILE_KINDS.includes(o.kind)) {
    const m = sniffImage(file.buffer);
    if (!m) throw new ApiError(400, "BAD_FILE", "Only PNG, JPEG, GIF or WebP images are allowed.");
    if (o.kind !== "image" && file.size > 5 * 1024 * 1024) throw new ApiError(413, "TOO_BIG", "Profile images must be under 5 MB.");
    mime = m; ext = m === "image/jpeg" ? "jpg" : m.split("/")[1];
  } else if (o.kind === "voice") {
    if (!VOICE.has(file.mimetype) || file.size > 10 * 1024 * 1024) throw new ApiError(400, "BAD_FILE", "Invalid voice message.");
    mime = file.mimetype === "audio/x-m4a" ? "audio/mp4" : file.mimetype; ext = "m4a";
  }
  const storage = crypto.randomBytes(16).toString("hex") + "." + ext;
  await fs.promises.writeFile(path.join(UPLOAD_DIR, storage), file.buffer, { mode: 0o600 });
  const name = path.basename(file.originalname || "file").replace(/[^\w.\- ]/g, "_").slice(0, 100);
  const [row] = await q(
    "INSERT INTO attachments(uploader_id,conversation_id,kind,mime,size,original_name,storage_name,duration_ms) VALUES($1,$2,$3,$4,$5,$6,$7,$8) RETURNING id",
    [userId, o.conversationId ?? null, o.kind, mime, file.size, name, storage, o.durationMs ?? null]);
  return row.id as string;
}
export async function deleteFile(id: string) {
  const [a] = await q("DELETE FROM attachments WHERE id=$1 RETURNING storage_name", [id]);
  if (a) await fs.promises.unlink(path.join(UPLOAD_DIR, a.storage_name)).catch(() => {});
}

export const filesRouter = Router();
filesRouter.get("/files/:id", async (req, res) => {
  const me = auth(req).userId;
  const [a] = await q("SELECT * FROM attachments WHERE id=$1", [uuid.parse(req.params.id)]);
  if (!a) throw notFound();
  if (PROFILE_KINDS.includes(a.kind)) { if (a.uploader_id !== me && (await blockedEither(me, a.uploader_id))) throw notFound(); }
  else if (!(await q("SELECT 1 FROM conversation_members WHERE conversation_id=$1 AND user_id=$2", [a.conversation_id, me])).length) throw notFound();
  const inline = a.kind !== "file";
  res.setHeader("Content-Type", inline ? a.mime : "application/octet-stream");
  res.setHeader("Content-Disposition", inline ? "inline" : `attachment; filename="${a.original_name}"`);
  res.setHeader("Cache-Control", "private, max-age=86400");
  res.sendFile(path.join(UPLOAD_DIR, a.storage_name), (err) => { if (err && !res.headersSent) res.status(404).end(); });
});
