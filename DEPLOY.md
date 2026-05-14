# Deploying IRC to Railway

A production deploy of this Spring Boot backend on Railway needs:

1. **One service**: the app itself (this repo's `Dockerfile`).
2. **Three plugins/services on Railway**: Postgres, Redis, RabbitMQ.
3. **One bucket on Cloudflare R2** (or any S3-compatible store).
4. **Environment variables** wired up as listed in `.env.example`.

Railway auto-detects the `Dockerfile` at the repo root and uses `railway.toml` for healthcheck + restart policy.

---

## 1. One-time setup

```bash
# Create the Railway project (or use the dashboard)
railway login
railway init                 # picks up Dockerfile + railway.toml
```

On the Railway dashboard:

1. **Add Postgres** plugin → it injects `DATABASE_URL` etc. Map to the Spring env vars (Variables tab → "Reference"):
   ```
   SPRING_DATASOURCE_URL       = jdbc:${{Postgres.DATABASE_URL.replace("postgresql://", "postgresql://")}}
   # ↑ simpler: copy the JDBC URL Railway shows under "Connect → JDBC"
   SPRING_DATASOURCE_USERNAME  = ${{Postgres.PGUSER}}
   SPRING_DATASOURCE_PASSWORD  = ${{Postgres.PGPASSWORD}}
   ```

2. **Add Redis** plugin → reference `REDIS_URL`:
   ```
   SPRING_DATA_REDIS_URL = ${{Redis.REDIS_URL}}
   ```

3. **Add RabbitMQ** plugin (search "RabbitMQ" in plugins / templates):
   ```
   SPRING_RABBITMQ_HOST          = ${{RabbitMQ.RABBITMQ_HOST}}
   SPRING_RABBITMQ_PORT          = ${{RabbitMQ.RABBITMQ_PORT}}
   SPRING_RABBITMQ_USERNAME      = ${{RabbitMQ.RABBITMQ_USER}}
   SPRING_RABBITMQ_PASSWORD      = ${{RabbitMQ.RABBITMQ_PASSWORD}}
   SPRING_RABBITMQ_VIRTUAL_HOST  = /
   ```

4. **Paste the rest of `.env.example`** into the Variables tab — every `__REPLACE_ME__` must be filled. The application **fails fast** on boot if `APP_JWT_SECRET`, `IRC_VERIFICATION_SECRET`, or the storage keys are missing.

5. **Set the profile**:
   ```
   SPRING_PROFILES_ACTIVE = prod
   ```
   (The `Dockerfile` defaults it to `prod` already; this is for safety / making it visible in the dashboard.)

6. **Deploy** — `railway up`, or just `git push` to the connected branch.

Railway auto-injects `PORT`; the app already binds to it via `server.port: ${PORT:${SERVER_PORT:8080}}`. Healthcheck is `/actuator/health` (configured in `railway.toml`).

---

## 2. Generating secrets

```bash
# JWT signing key — Base64, 64 bytes (~512 bits)
openssl rand -base64 64

# Verification secret — 32+ random chars
openssl rand -hex 24
```

---

## 3. Spring profiles in this repo

| Profile | When | What it changes |
|---|---|---|
| `dev` | Default. `./mvnw spring-boot:run` with no env vars set. | Localhost DB/Redis/Rabbit, `show-sql: true`, DEBUG logging, schema auto-update, dev-only JWT fallback secret. |
| `prod` | `SPRING_PROFILES_ACTIVE=prod` (set by Dockerfile + Railway). | All secrets required from env. `show-sql: false`. INFO logging. No SQL init. Graceful shutdown. No stacktraces in error bodies. Tomcat tuned. |

```bash
# Run locally as if it were production (against your local docker-compose):
SPRING_PROFILES_ACTIVE=prod \
APP_JWT_SECRET=$(openssl rand -base64 64) \
IRC_VERIFICATION_SECRET=$(openssl rand -hex 24) \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/irc \
SPRING_DATASOURCE_USERNAME=khi SPRING_DATASOURCE_PASSWORD= \
SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=6379 \
SPRING_RABBITMQ_HOST=localhost SPRING_RABBITMQ_USERNAME=guest SPRING_RABBITMQ_PASSWORD=guest \
./mvnw spring-boot:run
```

---

## 4. Builder choice: Dockerfile vs Nixpacks

Railway can build this repo two ways. **Both work**.

| Builder | When | Where the JDK comes from |
|---|---|---|
| **Dockerfile** (preferred) | `railway.toml` declares `builder = "DOCKERFILE"`. Use this for full control. | `eclipse-temurin:21-jdk` baked into the image. |
| **Nixpacks** (Railway default) | If you don't override the builder, or your service was created before `railway.toml` existed. | `nixpacks.toml` pins `NIXPACKS_JDK_VERSION = "21"`. |

The Maven `production` profile in `pom.xml` is intentionally minimal — it only flips `skipTests=true` so the cloud builder doesn't try to start Postgres/Redis/Rabbit. The actual production hardening lives in `application-prod.yaml` and is activated by `SPRING_PROFILES_ACTIVE=prod` (the Dockerfile sets this; the Nixpacks start command sets it via `-Dspring.profiles.active=prod`).

If your Railway build still fails with "release version 25 not supported", your service is using an old detected builder. Either:
- Open the service settings → Build → switch to **Dockerfile**, redeploy, **or**
- Trust `nixpacks.toml`'s `NIXPACKS_JDK_VERSION = "21"` and trigger a fresh deploy.

---

## 5. Building the Docker image locally

```bash
docker build -t irc:local .
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/irc \
  -e SPRING_DATASOURCE_USERNAME=khi -e SPRING_DATASOURCE_PASSWORD= \
  -e SPRING_DATA_REDIS_HOST=host.docker.internal \
  -e SPRING_RABBITMQ_HOST=host.docker.internal \
  -e SPRING_RABBITMQ_USERNAME=guest -e SPRING_RABBITMQ_PASSWORD=guest \
  -e APP_JWT_SECRET=$(openssl rand -base64 64) \
  -e IRC_VERIFICATION_SECRET=$(openssl rand -hex 24) \
  irc:local
```

Image is ~250 MB on JRE 25 (Eclipse Temurin). Build uses BuildKit cache mounts so subsequent builds skip dependency re-downloads.

---

## 6. Operational notes

- **Healthcheck path**: `GET /actuator/health` — returns 200 once Postgres, Redis and RabbitMQ are reachable. Railway routes traffic only after this passes.
- **Graceful shutdown**: configured (`server.shutdown: graceful`). In-flight requests complete before the JVM exits.
- **Schema migrations**: `ddl-auto: update` is on by default for fast iteration. Once stable, switch via env: `JPA_DDL_AUTO=validate` and use Flyway.
- **SSE / sticky sessions**: each `/stream` connection lives on the instance that opened it. If you scale to `numReplicas > 1` in `railway.toml`, enable session affinity at Railway's edge or accept that a client only sees events from posts on its own replica until the cross-replica Redis pub/sub fanout in `PostRealtimeSubscriber` warms up.
- **Logs**: stdout/stderr go straight to Railway's log UI. Format is human-readable; switch to JSON if you ship to an external log aggregator.
- **Metrics**: `/actuator/prometheus` is exposed. Restrict at the network layer or add basic-auth at the proxy.

---

## 7. Pre-flight checklist

Before flipping DNS:

- [ ] `APP_JWT_SECRET` is set, Base64, ≥256 bits.
- [ ] `IRC_VERIFICATION_SECRET` is set, ≥32 chars.
- [ ] `MAIL_PASSWORD` is the Gmail **App Password**, not the account password.
- [ ] `R2_*` keys point at the production bucket (not a test/dev one).
- [ ] `CORS_ORIGINS` lists only the production frontend origins.
- [ ] `IRC_BASE_URL` is the public canonical URL (shows up in emails + share links).
- [ ] `/actuator/health` returns `{"status":"UP"}` once on Railway.
- [ ] First test request (login + create-post) succeeds end-to-end.

That's it. Push to the connected branch, watch the logs, you're live.
