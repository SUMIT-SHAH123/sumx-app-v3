import nodemailer from "nodemailer";

const smtp = process.env.SMTP_HOST
  ? nodemailer.createTransport({ host: process.env.SMTP_HOST, port: Number(process.env.SMTP_PORT ?? 587), auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS } })
  : null;

export type MailMode = "resend" | "brevo" | "smtp" | "none";
export const mailMode: MailMode = process.env.RESEND_API_KEY ? "resend" : process.env.BREVO_API_KEY ? "brevo" : smtp ? "smtp" : "none";

function parseFrom(s: string) {
  const m = /^\s*"?([^"<]*?)"?\s*<([^>]+)>\s*$/.exec(s);
  return m ? { name: m[1].trim() || "SUM X", email: m[2].trim() } : { name: "SUM X", email: s.trim() };
}

export async function sendMail(to: string, subject: string, text: string) {
  const from = process.env.MAIL_FROM ?? "SUM X <onboarding@resend.dev>";
  try {
    if (mailMode === "resend") {
      const r = await fetch("https://api.resend.com/emails", { method: "POST",
        headers: { Authorization: `Bearer ${process.env.RESEND_API_KEY}`, "Content-Type": "application/json" }, body: JSON.stringify({ from, to: [to], subject, text }) });
      if (!r.ok) throw new Error(`Resend ${r.status}: ${await r.text()}`);
    } else if (mailMode === "brevo") {
      const f = parseFrom(from);
      const r = await fetch("https://api.brevo.com/v3/smtp/email", { method: "POST",
        headers: { "api-key": process.env.BREVO_API_KEY!, "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify({ sender: f, to: [{ email: to }], subject, textContent: text }) });
      if (!r.ok) throw new Error(`Brevo ${r.status}: ${await r.text()}`);
    } else if (mailMode === "smtp") {
      await smtp!.sendMail({ from, to, subject, text });
    } else {
      console.log(`\n[DEV MAIL - no mail provider configured] to=${to}\n${subject}\n${text}\n`);
    }
  } catch (e) {
    console.error("[mail] send failed:", e instanceof Error ? e.message : e); // never crash a request because email failed
  }
}
