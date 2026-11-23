import pg from "pg";

const raw = process.env.DATABASE_URL ?? "";
let host = "";
try { host = new URL(raw).hostname; } catch { /* leave empty */ }
// Hosted databases (Neon, Render external, Supabase...) need TLS; localhost / docker / Render-internal hosts (no dots) do not.
const isLocal = host === "" || host === "localhost" || host === "127.0.0.1" || !host.includes(".");
const mode = process.env.PGSSL; // "false" = never use TLS, "insecure" = TLS without certificate verification
const ssl = mode === "false" ? undefined : mode === "insecure" ? { rejectUnauthorized: false } : isLocal ? undefined : {};
// node-pg treats sslmode/channel_binding URL params differently from libpq; we control TLS via `ssl` above instead.
function clean(u: string) {
  try { const x = new URL(u); x.searchParams.delete("sslmode"); x.searchParams.delete("channel_binding"); return x.toString(); } catch { return u; }
}
export const pool = new pg.Pool({ connectionString: clean(raw), ssl, max: 10 });
export async function q<T = any>(text: string, params: any[] = []): Promise<T[]> {
  return (await pool.query(text, params)).rows as T[];
}
