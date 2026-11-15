// Vercel serverless function: POST /api/judge
// Holds the Anthropic key (env var) so it never ships inside the APK.
// Takes a redacted transcript, returns a verdict. Logs nothing, stores nothing.
import { validate, judge, statusFor, allow } from '../lib/judge-core.mjs';

export const config = { maxDuration: 30 };

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const ip = String(req.headers['x-forwarded-for'] ?? req.socket?.remoteAddress ?? 'unknown').split(',')[0].trim();
  if (!allow(ip)) return res.status(429).json({ error: 'too many requests' });

  try {
    const body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
    const input = validate(body);
    const { usage, ...verdict } = await judge(input);
    return res.status(200).json(verdict);
  } catch (err) {
    const [status, message] = err instanceof SyntaxError ? [400, 'invalid JSON'] : statusFor(err);
    return res.status(status).json({ error: message });
  }
}
