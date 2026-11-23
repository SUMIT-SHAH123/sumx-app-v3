import crypto from "node:crypto";
import jwt from "jsonwebtoken";
import type { NextFunction, Request, Response } from "express";
import { q } from "./db.js";

const SECRET = process.env.JWT_SECRET!;
if (!SECRET || SECRET.length < 24) throw new Error("JWT_SECRET must be set (24+ chars)");

export const sha256 = (s: string) => crypto.createHash("sha256").update(s).digest("hex");
export const newRefreshToken = () => crypto.randomBytes(48).toString("base64url");
export const signAccess = (userId: string, sessionId: string) =>
  jwt.sign({ sub: userId, sid: sessionId }, SECRET, { expiresIn: "15m" });

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

export async function requireAuth(req: Request, _res: Response, next: NextFunction) {
  try {
    const h = req.headers.authorization;
    if (!h?.startsWith("Bearer ")) throw new ApiError(401, "UNAUTHENTICATED", "Please log in.");
    const p = jwt.verify(h.slice(7), SECRET) as { sub: string; sid: string };
    const s = await q(
      "SELECT 1 FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.id=$1 AND s.revoked_at IS NULL AND s.expires_at>now() AND u.deleted_at IS NULL",
      [p.sid]
    );
    if (!s.length) throw new ApiError(401, "SESSION_ENDED", "Session has ended.");
    (req as any).auth = { userId: p.sub, sessionId: p.sid };
    next();
  } catch (e) {
    next(e instanceof ApiError ? e : new ApiError(401, "UNAUTHENTICATED", "Please log in."));
  }
}
export const auth = (req: Request) => (req as any).auth as { userId: string; sessionId: string };

export async function verifyAccess(token: string) {
  const p = jwt.verify(token, SECRET) as { sub: string; sid: string };
  const s = await q(
    "SELECT 1 FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.id=$1 AND s.revoked_at IS NULL AND s.expires_at>now() AND u.deleted_at IS NULL", [p.sid]);
  if (!s.length) throw new ApiError(401, "SESSION_ENDED", "Session has ended.");
  return { userId: p.sub, sessionId: p.sid };
}
