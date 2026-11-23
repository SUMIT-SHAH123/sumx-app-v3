import type { Server } from "node:http";
import { WebSocketServer } from "ws";
import { q } from "./db.js";
import { verifyAccess } from "./security.js";
import { conns, emit, isOnline } from "./hub.js";
import { broadcastPresence } from "./common.js";
import { handleCallMessage, onCallDisconnect } from "./calls.js";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function attachRealtime(server: Server) {
  const wss = new WebSocketServer({ server, path: "/ws", maxPayload: 64 * 1024 });
  wss.on("connection", (ws) => {
    let userId: string | null = null;
    const timer = setTimeout(() => { if (!userId) ws.close(4001, "auth timeout"); }, 5000);
    ws.on("message", async (raw) => {
      let m: any;
      try { m = JSON.parse(raw.toString()); } catch { return ws.close(4002, "bad frame"); }
      if (!userId) { // first frame must authenticate; the token is never placed in the URL
        try {
          if (m.type !== "auth") throw new Error();
          const a = await verifyAccess(String(m.token));
          const first = !isOnline(a.userId); userId = a.userId;
          if (!conns.has(userId)) conns.set(userId, new Set());
          conns.get(userId)!.add(ws);
          ws.send(JSON.stringify({ type: "ready", data: {} }));
          if (first) void broadcastPresence(userId);
        } catch { ws.close(4001, "unauthorized"); }
        return;
      }
      if (typeof m.type === "string" && m.type.startsWith("call.")) return void handleCallMessage(userId, m);
      if (m.type === "typing" && UUID.test(String(m.conversationId))) {
        const [r] = await q(`SELECT o.user_id FROM conversation_members me JOIN conversation_members o ON o.conversation_id=me.conversation_id AND o.user_id<>me.user_id
                             WHERE me.conversation_id=$1 AND me.user_id=$2`, [m.conversationId, userId]).catch(() => []);
        if (r) emit(r.user_id, "typing", { conversationId: m.conversationId, userId });
      }
    });
    ws.on("close", async () => {
      clearTimeout(timer); if (!userId) return;
      const set = conns.get(userId); set?.delete(ws);
      if (!set?.size) {
        conns.delete(userId); onCallDisconnect(userId);
        await q("UPDATE users SET last_seen_at=now() WHERE id=$1", [userId]).catch(() => {});
        void broadcastPresence(userId);
      }
    });
  });
}
