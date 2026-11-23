import { WebSocket } from "ws";
export const conns = new Map<string, Set<WebSocket>>();
export const isOnline = (id: string) => (conns.get(id)?.size ?? 0) > 0;
export function emit(userId: string, type: string, data: unknown) {
  const frame = JSON.stringify({ type, data });
  conns.get(userId)?.forEach((ws) => ws.readyState === WebSocket.OPEN && ws.send(frame));
}
