// The app sends its events here (batches). We add where the phone is (Vercel's edge knows the
// country/city from the IP; the IP itself is never stored), check who is signed in, and hand
// the batch to Supabase with the server-only key. Same idea as fxabsolute.com's /api/track.
const BASE = 'https://whfxrovgvulmhqkhumuz.supabase.co'
const PUBLIC_KEY = 'sb_publishable_pdhpMolBnG71stSPc_NzTw_Kjs9KpQ2'

function head(req, name) {
  const v = req.headers[name]
  if (!v) return null
  try { return decodeURIComponent(Array.isArray(v) ? v[0] : v) } catch { return null }
}

async function readJson(req) {
  if (req.body && typeof req.body === 'object') return req.body
  let raw = ''
  for await (const chunk of req) { raw += chunk; if (raw.length > 200_000) throw new Error('too big') }
  return JSON.parse(raw || '{}')
}

// never trust a claimed user id: ask Supabase who this login token belongs to
async function whoIs(token) {
  if (!token || typeof token !== 'string' || token.length > 4000) return null
  const r = await fetch(`${BASE}/auth/v1/user`, { headers: { apikey: PUBLIC_KEY, Authorization: `Bearer ${token}` } })
  if (!r.ok) return null
  const u = await r.json().catch(() => null)
  return u?.id || null
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' })
  const secret = process.env.SUPABASE_SECRET
  if (!secret) return res.status(500).json({ error: 'not configured' })
  let body
  try { body = await readJson(req) } catch { return res.status(400).json({ error: 'bad json' }) }
  const deviceId = String(body.device_id || '')
  if (!/^[A-Za-z0-9-]{8,64}$/.test(deviceId)) return res.status(400).json({ error: 'bad device' })
  const events = Array.isArray(body.events) ? body.events.slice(0, 60) : []

  const p = {
    device_id: deviceId,
    user_id: await whoIs(body.token).catch(() => null),
    app_version: String(body.app_version || '').slice(0, 20),
    device: typeof body.device === 'object' && body.device ? body.device : {},
    geo: {
      country: head(req, 'x-vercel-ip-country'),
      region: head(req, 'x-vercel-ip-country-region'),
      city: head(req, 'x-vercel-ip-city'),
      timezone: head(req, 'x-vercel-ip-timezone'),
    },
    events: events.map((e) => ({
      name: String(e?.name || '').slice(0, 40),
      at: Number(e?.at) || Date.now(),
      props: typeof e?.props === 'object' && e.props ? e.props : {},
    })).filter((e) => e.name),
  }
  const r = await fetch(`${BASE}/rest/v1/rpc/pa_ingest`, {
    method: 'POST',
    headers: { apikey: secret, 'Content-Type': 'application/json' }, // new sb_secret keys go in apikey only
    body: JSON.stringify({ p }),
  })
  if (!r.ok) {
    console.error('ingest failed', r.status, (await r.text()).slice(0, 300))
    return res.status(502).json({ error: 'store failed' })
  }
  return res.status(200).json({ ok: true, n: p.events.length })
}
