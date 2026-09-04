# Deploying SeatLock

A runbook for putting this on the internet with a clickable Swagger URL, at roughly zero cost.

**Target:** Fly.io for the app, [Neon](https://neon.tech) for Postgres, [Upstash](https://upstash.com) for Redis. All three have free tiers that comfortably cover a demo. Fly is a few dollars a month if you want it always-on instead of scaling to zero.

> Pricing and free-tier limits on all three change regularly. Check them before assuming this is free.

---

## Before you start

You need accounts on Fly.io, Neon and Upstash, and the Fly CLI:

```bash
# Windows (PowerShell)
iwr https://fly.io/install.ps1 -useb | iex
fly auth login
```

---

## 1. Postgres (Neon)

Create a project, then copy the connection string. It looks like:

```
postgresql://USER:PASSWORD@ep-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
```

Three things to convert it into what the app wants:

- The app takes `DB_URL`, `DB_USER` and `DB_PASSWORD` **separately** — split the credentials out of the URL.
- JDBC needs a `jdbc:` prefix: `jdbc:postgresql://ep-xxxx.../neondb?sslmode=require`
- **Keep `sslmode=require`.** Neon refuses plaintext connections, and the failure is an unhelpful timeout rather than a clear error.

Nothing else is needed. Flyway creates the schema on first boot and seeds three demo events.

## 2. Redis (Upstash)

Create a database and copy the **endpoint, port and password** (not the REST URL — this app speaks the Redis protocol, not Upstash's HTTP API).

Upstash is TLS-only, which `fly.toml` already sets via `REDIS_SSL=true`.

## 3. Secrets

Everything sensitive goes through `fly secrets`, never `fly.toml`:

```bash
fly secrets set \
  DB_URL="jdbc:postgresql://ep-xxxx.aws.neon.tech/neondb?sslmode=require" \
  DB_USER="your_neon_user" \
  DB_PASSWORD="your_neon_password" \
  REDIS_HOST="your-db.upstash.io" \
  REDIS_PORT="6379" \
  REDIS_PASSWORD="your_upstash_password" \
  JWT_SECRET="$(openssl rand -base64 48)"
```

Generate `JWT_SECRET` randomly — do not reuse the development default. The app **refuses to start** on a secret under 32 bytes, so a weak one fails loudly at boot rather than quietly signing tokens anyone can forge.

## 4. Deploy

```bash
fly launch --no-deploy   # first time only; keeps the committed fly.toml
fly deploy
fly open /swagger-ui.html
```

## 5. Check it actually works

```bash
fly status
fly logs

curl -s https://seatlock.fly.dev/actuator/health | jq
curl -s https://seatlock.fly.dev/api/v1/events | jq '.content[0]'
```

A full booking, end to end:

```bash
BASE=https://seatlock.fly.dev

TOKEN=$(curl -s -X POST $BASE/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"correct-horse-battery","fullName":"Demo User"}' \
  | jq -r .accessToken)

SEAT=$(curl -s $BASE/api/v1/events/1/seats | jq '.seats[0].eventSeatId')

HOLD=$(curl -s -X POST $BASE/api/v1/events/1/holds \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"eventSeatIds\":[$SEAT]}" | jq -r .holdId)

curl -s -X POST $BASE/api/v1/bookings \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d "{\"holdId\":\"$HOLD\"}" | jq
```

---

## Things that will bite you

**Cold starts.** With `min_machines_running = 0` the first request after an idle period waits 20–30s for the JVM and Flyway. If you are sending the link to someone, hit it yourself first. Set `min_machines_running = 1` to avoid it, at the cost of running continuously.

**The reaper sleeps too.** Expired holds are reclaimed by a scheduled job, which does not run while the machine is stopped. Holds then expire late rather than on time. Harmless for a demo, but know the answer if asked — the seat is still correctly held, just for longer than advertised.

**Memory.** 512MB is tight for Spring Boot with JPA, Redis and Security. If the logs show `ExitOnOutOfMemoryError`, raise the VM to `1gb` rather than increasing `MaxRAMPercentage` — the JVM needs room outside the heap for metaspace, thread stacks and direct buffers.

**Connection limits.** `DB_POOL_SIZE=5` is set for a reason. The local default of 20 will exhaust a free-tier Postgres, and Hikari fails at startup rather than degrading.

**This is a public write API.** Anyone who finds the URL can register and book seats. That is fine for a demo nobody knows about, but there is no rate limiting: consider taking it down between interviews, or adding a limit before sharing it widely.

**Redis going down is survivable.** Verified by `RedisOutageIT`: the gate fails open and bookings continue against Postgres, slower but correct. Watch `seatlock_holds_total{outcome="gate_unavailable"}` on `/actuator/prometheus` — a non-zero rate means the fast path is gone even though nothing is erroring.

---

## Alternatives

**Railway** is the easiest path: connect the repo, it detects the Dockerfile, and Postgres and Redis are one click each. No free tier any more (~$5/month), but almost no configuration.

**Render** has a free web service tier, though it sleeps aggressively and its free Postgres is time-limited. Pair it with Neon rather than using its own database.

**Oracle Cloud Always Free** gives a genuinely free ARM VM large enough to run `docker compose up` as-is — including OrderFlow with Kafka. Far more setup and maintenance, but the only realistically free way to host the full event-driven project.
