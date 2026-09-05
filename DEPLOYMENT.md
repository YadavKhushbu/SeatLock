# Deploying SeatLock

A runbook for putting this on the internet with a clickable Swagger URL.

**Target: [Render](https://render.com) for the app, [Neon](https://neon.tech) for Postgres, [Upstash](https://upstash.com) for Redis.** All three have free tiers, and none of them needed a card at the time of writing.

> Free-tier terms change constantly. Verify before assuming any of this is still free.

### Why not Fly.io

`fly.toml` is still in this repo and still correct, but Fly now **requires a credit card before you can create an app at all**:

```
Error: We need your payment information to continue!
```

Its free allowance still exists, and with `auto_stop_machines` the running cost would be small, but a card on file is a precondition. Use `fly.toml` instead if you already have Fly billing set up: it specifies a 1GB machine and is the better home for this if cost is not the constraint.

### The trade Render makes

| | Render free | Fly (paid) |
|---|---|---|
| Card required | No | **Yes** |
| Memory | 512MB, fixed | 1GB, adjustable |
| Idle behaviour | Sleeps after ~15 min | Scales to zero |
| Cold start | ~50s+ | ~20-30s |

512MB is enough, verified below, but there is no headroom to buy if that changes.

---

## Before you start

Accounts on Render, Neon and Upstash. No CLI needed: Render deploys from GitHub, so everything happens in the browser and in this repo.

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

## 3. Deploy on Render

`render.yaml` in this repo is a Blueprint: Render reads it and creates the service with the runtime, region, health check and JVM settings already set.

1. Render dashboard -> **New -> Blueprint**
2. Connect the GitHub repo `YadavKhushbu/SeatLock`
3. Render reads `render.yaml` and prompts for the values marked `sync: false`:

| Variable | Value |
|---|---|
| `DB_URL` | `jdbc:postgresql://EP-XXX.neon.tech/neondb?sslmode=require` |
| `DB_USER` | your Neon user |
| `DB_PASSWORD` | your Neon password |
| `REDIS_HOST` | `your-db.upstash.io` |
| `REDIS_PORT` | `6379` |
| `REDIS_PASSWORD` | your Upstash password |

`JWT_SECRET` is deliberately **not** in that list. `render.yaml` marks it `generateValue: true`, so Render mints a long random value and keeps it stable across deploys; it never passes through a terminal, a clipboard or a chat window. The app refuses to start on a secret under 32 bytes, so a weak one fails loudly at boot rather than quietly signing forgeable tokens.

4. **Apply.** The first build takes several minutes, because Maven builds inside Docker.

## 4. Check it actually works

Watch the deploy log for `Started SeatLockApplication`, and for Flyway applying `V1__init` and `V2__seed_demo_data` on first boot.

```bash
BASE=https://seatlock.onrender.com   # your actual URL from the dashboard

curl -s $BASE/actuator/health | jq
curl -s $BASE/api/v1/events | jq '.content[0]'
```

The first request after an idle period takes ~50s while the container wakes. That is the free tier, not a failure.

A full booking, end to end:

```bash
BASE=https://seatlock.onrender.com   # your actual URL

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

**Cold starts are worse here than on Fly.** Render's free tier sleeps the container after ~15 minutes idle, and waking it means a fresh container plus JVM start plus Flyway: expect 50s or more. **Always hit the URL yourself before sending it to anyone** - a recruiter who clicks and sees a spinner will not wait.

**The reaper sleeps too.** Expired holds are reclaimed by a scheduled job, which does not run while the container is asleep. Holds then expire late rather than on time. Harmless for a demo, but know the answer if asked — the seat is still correctly held, just for longer than advertised.

**Memory — measured, not estimated.** The image was run locally under a hard 512MB cap before writing this:

| | |
|---|---|
| Boot to readiness | 18s |
| At rest | 432MB / 512MB (84%) |
| After 600 requests | 446MB / 512MB (87%) |
| OOM kills | none |

512MB genuinely works. `fly.toml` still specifies **1gb**, because `ExitOnOutOfMemoryError` kills the JVM outright instead of degrading — so crossing the line means a hard restart mid-request, and 13% headroom is not much to absorb a spike. With `auto_stop_machines` the machine bills only while serving, so the larger size costs little.

If you do need more room later, raise the VM size rather than `MaxRAMPercentage`: the JVM needs space outside the heap for metaspace, thread stacks and direct buffers, and giving the heap a bigger share of a small box makes OOM more likely, not less.

**Connection limits.** `DB_POOL_SIZE=5` is set for a reason. The local default of 20 will exhaust a free-tier Postgres, and Hikari fails at startup rather than degrading.

**This is a public write API.** Anyone who finds the URL can register and book seats. That is fine for a demo nobody knows about, but there is no rate limiting: consider taking it down between interviews, or adding a limit before sharing it widely.

**Redis going down is survivable.** Verified by `RedisOutageIT`: the gate fails open and bookings continue against Postgres, slower but correct. Watch `seatlock_holds_total{outcome="gate_unavailable"}` on `/actuator/prometheus` — a non-zero rate means the fast path is gone even though nothing is erroring.

---

## Alternatives

**Fly.io** is the better home for this once a card is on file: 1GB instead of 512MB, faster cold starts, and `fly.toml` in this repo is ready to use. `fly deploy` is all that stands between you and a running app.

**Railway** connects to the repo, detects the Dockerfile, and adds Postgres and Redis in one click each. Almost no configuration, but no free tier (~$5/month).

**Render's own Postgres** is deliberately unused here. Its free database is time-limited, which is a poor property for something meant to sit on a CV for months; Neon's free tier persists.

**Oracle Cloud Always Free** gives a genuinely free ARM VM large enough to run `docker compose up` as-is — including OrderFlow with Kafka. Far more setup and maintenance, but the only realistically free way to host the full event-driven project.
