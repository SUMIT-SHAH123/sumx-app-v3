import crypto from "node:crypto";
import { Router, type Request } from "express";
import rateLimit from "express-rate-limit";
import argon2 from "argon2";
import { OAuth2Client } from "google-auth-library";
import { z } from "zod";
import { pool, q } from "./db.js";
import { mailMode, sendMail } from "./mail.js";
import { ApiError, auth, newRefreshToken, requireAuth, sha256, signAccess } from "./security.js";

export const authRouter = Router();
const strict = rateLimit({ windowMs: 15 * 60_000, limit: 40, standardHeaders: true });
const google = new OAuth2Client();

const username = z.string().trim().toLowerCase().regex(/^[a-z0-9_]{3,20}$/, "Username must be 3-20 letters, numbers or underscores.");
const password = z.string().min(8, "Password must be at least 8 characters.").max(128);
const email = z.string().trim().toLowerCase().email("Enter a valid email.").max(254);

// ---------- helpers ----------
async function makeSumxId(uname: string, db = pool): Promise<string> {
  for (let i = 0; i < 20; i++) {
    const id = `@${uname}${crypto.randomInt(1000, 10000)}`;
    const { rows } = await db.query("SELECT 1 FROM users WHERE sumx_id=$1", [id]);
    if (!rows.length) return id;
  }
  throw new ApiError(500, "SERVER", "Could not allocate SUM X ID.");
}

const publicUser = (u: any) => ({
  id: u.id, email: u.email, emailVerified: u.email_verified, username: u.username,
  sumxId: u.sumx_id, displayName: u.display_name, createdAt: u.created_at,
});

async function issueSession(req: Request, userId: string, remember: boolean) {
  const refresh = newRefreshToken();
  const days = remember ? 30 : 1;
  const [s] = await q(
    `INSERT INTO sessions(user_id, refresh_hash, device, ip, expires_at)
     VALUES($1,$2,$3,$4, now() + ($5 || ' days')::interval) RETURNING id`,
    [userId, sha256(refresh), String(req.headers["user-agent"] ?? "").slice(0, 200), req.ip, String(days)]
  );
  const [u] = await q("SELECT * FROM users WHERE id=$1", [userId]);
  return { accessToken: signAccess(userId, s.id), refreshToken: refresh, user: publicUser(u) };
}

async function sendCode(userId: string, to: string, purpose: "verify_email" | "reset_password") {
  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
  await q("UPDATE email_codes SET used_at=now() WHERE user_id=$1 AND purpose=$2 AND used_at IS NULL", [userId, purpose]);
  await q("INSERT INTO email_codes(user_id,purpose,code_hash,expires_at) VALUES($1,$2,$3, now() + interval '15 minutes')",
    [userId, purpose, sha256(`${userId}:${purpose}:${code}`)]);
  const subject = purpose === "verify_email" ? "Verify your SUM X email" : "Reset your SUM X password";
  await sendMail(to, subject, `Your SUM X code is ${code}. It expires in 15 minutes. If you didn't request this, ignore this email.`);
}

async function checkCode(userId: string, purpose: string, code: string) {
  const [row] = await q(
    "SELECT * FROM email_codes WHERE user_id=$1 AND purpose=$2 AND used_at IS NULL AND expires_at>now() ORDER BY expires_at DESC LIMIT 1",
    [userId, purpose]);
  const bad = new ApiError(400, "BAD_CODE", "That code is invalid or expired.");
  if (!row || row.attempts >= 5) throw bad;
  if (row.code_hash !== sha256(`${userId}:${purpose}:${code}`)) {
    await q("UPDATE email_codes SET attempts=attempts+1 WHERE id=$1", [row.id]);
    throw bad;
  }
  await q("UPDATE email_codes SET used_at=now() WHERE id=$1", [row.id]);
}

export async function verifyGoogle(idToken: string) {
  const aud = process.env.GOOGLE_WEB_CLIENT_ID;
  if (!aud) throw new ApiError(503, "GOOGLE_NOT_CONFIGURED", "Google sign-in is not configured on the server.");
  try {
    const ticket = await google.verifyIdToken({ idToken, audience: aud });
    const p = ticket.getPayload()!;
    if (!p.email || !p.email_verified) throw new Error("unverified");
    return { sub: p.sub, email: p.email.toLowerCase(), name: p.name ?? p.email.split("@")[0] };
  } catch {
    throw new ApiError(401, "GOOGLE_INVALID", "Google sign-in failed. Please try again.");
  }
}

const ageOk = (dob: string) => {
  const d = new Date(dob + "T00:00:00Z"); const now = new Date();
  let age = now.getUTCFullYear() - d.getUTCFullYear();
  if (now < new Date(Date.UTC(now.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()))) age--;
  return !isNaN(age) && age >= 13 && age < 120;
};

// ---------- routes ----------
authRouter.post("/auth/register", strict, async (req, res) => {
  const b = z.object({
    displayName: z.string().trim().min(1, "Display name is required.").max(32),
    username, email, password,
    dob: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Enter your date of birth."),
    acceptTerms: z.literal(true, { errorMap: () => ({ message: "You must accept the Terms and Privacy Policy." }) }),
  }).parse(req.body);
  if (!ageOk(b.dob)) throw new ApiError(400, "AGE", "You must be at least 13 years old to use SUM X.");

  const hash = await argon2.hash(b.password, { type: argon2.argon2id });
  const client = await pool.connect();
  let userId: string;
  try {
    await client.query("BEGIN");
    const sumxId = await makeSumxId(b.username, client as any);
    const { rows } = await client.query(
      "INSERT INTO users(email,password_hash,username,sumx_id,display_name,date_of_birth) VALUES($1,$2,$3,$4,$5,$6) RETURNING id",
      [b.email, hash, b.username, sumxId, b.displayName, b.dob]);
    userId = rows[0].id;
    await client.query("INSERT INTO profiles(user_id) VALUES($1)", [userId]);
    await client.query("COMMIT");
  } catch (e: any) {
    await client.query("ROLLBACK");
    if (e.code === "23505") {
      if (String(e.constraint).includes("email")) throw new ApiError(409, "EMAIL_TAKEN", "An account with this email already exists.");
      throw new ApiError(409, "USERNAME_TAKEN", "That username is taken.");
    }
    throw e;
  } finally { client.release(); }
  if (mailMode === "none") { // no way to send email on this server: verify automatically so people aren't locked out
    await q("UPDATE users SET email_verified=true WHERE id=$1", [userId]);
    return void res.status(201).json({ ...(await issueSession(req, userId, true)), verificationRequired: false });
  }
  await sendCode(userId, b.email, "verify_email");
  res.status(201).json({ email: b.email, message: "Account created. Enter the code we emailed you." });
});

authRouter.post("/auth/verify-email", strict, async (req, res) => {
  const b = z.object({ email, code: z.string().regex(/^\d{6}$/), rememberMe: z.boolean().optional() }).parse(req.body);
  const [u] = await q("SELECT id FROM users WHERE email=$1 AND deleted_at IS NULL", [b.email]);
  if (!u) throw new ApiError(400, "BAD_CODE", "That code is invalid or expired.");
  await checkCode(u.id, "verify_email", b.code);
  await q("UPDATE users SET email_verified=true WHERE id=$1", [u.id]);
  res.json(await issueSession(req, u.id, b.rememberMe ?? true));
});

authRouter.post("/auth/resend-verification", strict, async (req, res) => {
  const b = z.object({ email }).parse(req.body);
  const [u] = await q("SELECT id FROM users WHERE email=$1 AND email_verified=false AND deleted_at IS NULL", [b.email]);
  if (u) await sendCode(u.id, b.email, "verify_email");
  res.json({ message: "If that account needs verification, a new code was sent." });
});

authRouter.post("/auth/login", strict, async (req, res) => {
  const b = z.object({ identifier: z.string().trim().toLowerCase().min(1).max(254), password: z.string().max(128), rememberMe: z.boolean().optional() }).parse(req.body);
  const [u] = await q(
    "SELECT * FROM users WHERE deleted_at IS NULL AND (email=$1 OR username=$1 OR sumx_id=$1) LIMIT 1", [b.identifier]);
  const fail = new ApiError(401, "INVALID_CREDENTIALS", "Incorrect login or password.");
  if (!u || !u.password_hash || !(await argon2.verify(u.password_hash, b.password))) throw fail;
  if (!u.email_verified && mailMode === "none") { await q("UPDATE users SET email_verified=true WHERE id=$1", [u.id]); u.email_verified = true; }
  if (!u.email_verified) {
    await sendCode(u.id, u.email, "verify_email");
    throw new ApiError(403, "EMAIL_NOT_VERIFIED", "Verify your email first. We sent a new code.");
  }
  res.json(await issueSession(req, u.id, b.rememberMe ?? false));
});

authRouter.post("/auth/google", strict, async (req, res) => {
  const b = z.object({ idToken: z.string().min(20), rememberMe: z.boolean().optional() }).parse(req.body);
  const g = await verifyGoogle(b.idToken);
  const [linked] = await q("SELECT user_id FROM linked_accounts WHERE provider='google' AND provider_user_id=$1", [g.sub]);
  if (linked) return void res.json(await issueSession(req, linked.user_id, b.rememberMe ?? true));

  const [existing] = await q("SELECT id FROM users WHERE email=$1 AND deleted_at IS NULL", [g.email]);
  if (existing) // never auto-merge: ownership of the SUM X account must be proven first
    throw new ApiError(409, "ACCOUNT_EXISTS", "An account with this email already exists. Log in with your password, then connect Google in Settings.");

  const client = await pool.connect();
  let userId: string;
  try {
    await client.query("BEGIN");
    let base = g.email.split("@")[0].toLowerCase().replace(/[^a-z0-9_]/g, "").slice(0, 14).padEnd(3, "x");
    let uname = base;
    for (let i = 0; i < 10; i++) {
      const { rows } = await client.query("SELECT 1 FROM users WHERE username=$1", [uname]);
      if (!rows.length) break;
      uname = `${base}${crypto.randomInt(100, 1000)}`;
    }
    const sumxId = await makeSumxId(uname, client as any);
    const { rows } = await client.query(
      "INSERT INTO users(email,email_verified,username,sumx_id,display_name) VALUES($1,true,$2,$3,$4) RETURNING id",
      [g.email, uname, sumxId, g.name.slice(0, 32)]);
    userId = rows[0].id;
    await client.query("INSERT INTO profiles(user_id) VALUES($1)", [userId]);
    await client.query("INSERT INTO linked_accounts(user_id,provider,provider_user_id,email) VALUES($1,'google',$2,$3)", [userId, g.sub, g.email]);
    await client.query("COMMIT");
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
  res.status(201).json(await issueSession(req, userId, b.rememberMe ?? true));
});

authRouter.post("/auth/refresh", async (req, res) => {
  const b = z.object({ refreshToken: z.string().min(20) }).parse(req.body);
  const [s] = await q("SELECT * FROM sessions WHERE refresh_hash=$1 AND revoked_at IS NULL AND expires_at>now()", [sha256(b.refreshToken)]);
  if (!s) throw new ApiError(401, "SESSION_ENDED", "Session has ended.");
  const next = newRefreshToken(); // rotate on every use
  await q("UPDATE sessions SET refresh_hash=$1, last_used_at=now() WHERE id=$2", [sha256(next), s.id]);
  res.json({ accessToken: signAccess(s.user_id, s.id), refreshToken: next });
});

authRouter.post("/auth/logout", requireAuth, async (req, res) => {
  await q("UPDATE sessions SET revoked_at=now() WHERE id=$1", [auth(req).sessionId]);
  res.json({ ok: true });
});
authRouter.post("/auth/logout-all", requireAuth, async (req, res) => {
  await q("UPDATE sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL", [auth(req).userId]);
  res.json({ ok: true });
});

authRouter.post("/auth/forgot-password", strict, async (req, res) => {
  const b = z.object({ email }).parse(req.body);
  const [u] = await q("SELECT id FROM users WHERE email=$1 AND deleted_at IS NULL", [b.email]);
  if (u) await sendCode(u.id, b.email, "reset_password");
  res.json({ message: "If an account exists for that email, a reset code was sent." });
});

authRouter.post("/auth/reset-password", strict, async (req, res) => {
  const b = z.object({ email, code: z.string().regex(/^\d{6}$/), newPassword: password }).parse(req.body);
  const [u] = await q("SELECT id FROM users WHERE email=$1 AND deleted_at IS NULL", [b.email]);
  if (!u) throw new ApiError(400, "BAD_CODE", "That code is invalid or expired.");
  await checkCode(u.id, "reset_password", b.code);
  await q("UPDATE users SET password_hash=$1, email_verified=true WHERE id=$2", [await argon2.hash(b.newPassword, { type: argon2.argon2id }), u.id]);
  await q("UPDATE sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL", [u.id]); // sign out everywhere
  res.json({ message: "Password updated. Please log in." });
});

// ---------- account ----------
authRouter.get("/me", requireAuth, async (req, res) => {
  const [u] = await q("SELECT * FROM users WHERE id=$1", [auth(req).userId]);
  const [p] = await q("SELECT avatar_url,banner_color,accent_color,about,pronouns,custom_status FROM profiles WHERE user_id=$1", [u.id]);
  res.json({ user: publicUser(u), profile: p });
});

authRouter.get("/auth/methods", requireAuth, async (req, res) => {
  const [u] = await q("SELECT password_hash IS NOT NULL AS has_password FROM users WHERE id=$1", [auth(req).userId]);
  const g = await q("SELECT email FROM linked_accounts WHERE user_id=$1 AND provider='google'", [auth(req).userId]);
  res.json({ password: u.has_password, google: g[0]?.email ?? null });
});

authRouter.post("/auth/link/google", requireAuth, strict, async (req, res) => {
  const b = z.object({ idToken: z.string().min(20) }).parse(req.body);
  const g = await verifyGoogle(b.idToken);
  const [taken] = await q("SELECT user_id FROM linked_accounts WHERE provider='google' AND provider_user_id=$1", [g.sub]);
  if (taken && taken.user_id !== auth(req).userId) throw new ApiError(409, "GOOGLE_IN_USE", "That Google account is linked to another SUM X account.");
  const [me] = await q("SELECT email FROM users WHERE id=$1", [auth(req).userId]);
  if (g.email !== me.email) throw new ApiError(400, "EMAIL_MISMATCH", "The Google account's email must match your SUM X email.");
  await q("INSERT INTO linked_accounts(user_id,provider,provider_user_id,email) VALUES($1,'google',$2,$3) ON CONFLICT DO NOTHING", [auth(req).userId, g.sub, g.email]);
  res.json({ google: g.email });
});

authRouter.delete("/auth/link/google", requireAuth, async (req, res) => {
  const [u] = await q("SELECT password_hash IS NOT NULL AS has_password FROM users WHERE id=$1", [auth(req).userId]);
  if (!u.has_password) throw new ApiError(400, "LAST_METHOD", "Set a password first (use Forgot password) so you don't lose access.");
  await q("DELETE FROM linked_accounts WHERE user_id=$1 AND provider='google'", [auth(req).userId]);
  res.json({ ok: true });
});

authRouter.get("/auth/username-available", async (req, res) => {
  const u = username.safeParse(req.query.u);
  if (!u.success) return void res.json({ available: false });
  res.json({ available: (await q("SELECT 1 FROM users WHERE username=$1", [u.data])).length === 0 });
});
