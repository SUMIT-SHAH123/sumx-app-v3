import "express-async-errors"; // Express 4 does not forward errors thrown in async handlers; this makes them reach the error middleware
import "dotenv/config";
import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import cors from "cors";
import rateLimit from "express-rate-limit";
import { ZodError } from "zod";
import http from "node:http";
import { q } from "./db.js";
import { mailMode } from "./mail.js";
import { authRouter } from "./auth.js";
import { socialRouter } from "./social.js";
import { messagesRouter } from "./messages.js";
import { profileRouter } from "./profile.js";
import { accountRouter } from "./account.js";
import { callsRouter } from "./calls.js";
import { filesRouter } from "./files.js";
import { requireAuth } from "./security.js";
import { attachRealtime } from "./realtime.js";
import { ApiError } from "./security.js";

const app = express();
app.set("trust proxy", 1);
app.use((req, _res, next) => { req.url = req.url.replace(/\/{2,}/g, "/"); next(); }); // "https://host//auth/login" must still match
app.use(helmet());
app.use(cors());               // native app: no browser cookies; tighten if a web client is added
app.use(express.json({ limit: "100kb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 120 }));
app.get("/", (_req, res) => res.json({ name: "SUM X API", ok: true }));
app.get("/health", async (_req, res) => {
  let db = false; try { await q("SELECT 1"); db = true; } catch { /* reported below */ }
  res.json({ ok: true, db, mail: mailMode });
});
app.use("/", authRouter);
app.use("/", requireAuth, socialRouter, messagesRouter, profileRouter, accountRouter, callsRouter, filesRouter);

app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  if (err instanceof ApiError) return res.status(err.status).json({ error: { code: err.code, message: err.message } });
  if (err instanceof ZodError)
    return res.status(400).json({ error: { code: "VALIDATION", message: err.issues[0]?.message ?? "Invalid input" } });
  if ((err as any)?.code === "LIMIT_FILE_SIZE") return res.status(413).json({ error: { code: "TOO_BIG", message: "That file is too large (15 MB max)." } });
  console.error(err);
  res.status(500).json({ error: { code: "SERVER", message: "Something went wrong." } });
});

process.on("unhandledRejection", (e) => console.error("[unhandledRejection]", e)); // one bad request must never take the server down
process.on("uncaughtException", (e) => console.error("[uncaughtException]", e));
const server = http.createServer(app);
attachRealtime(server);
server.listen(Number(process.env.PORT ?? 4000), () => console.log("SUM X API listening"));
