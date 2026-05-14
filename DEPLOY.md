# Deploy to Railway

The whole setup is **two files plus a `.gitignore`**:

```
src/main/resources/application.yaml   ← the only config file
nixpacks.toml                          ← pins JDK 21
.env.example                           ← list of vars to set on Railway
```

There is **no Spring profile, no Docker, no `application-prod.yaml`**. The single `application.yaml` reads everything from environment variables and falls back to localhost defaults so `./mvnw spring-boot:run` Just Works locally.

---

## 1. One-time Railway setup

1. Create the Railway project, point it at this repo's `main` branch.
2. Add three plugins in the dashboard:
   - **Postgres**
   - **Redis**
   - **RabbitMQ**
3. Open **Variables** on your service and paste from `.env.example`. Replace each `__REPLACE_ME__` with a real value. For the plugin connection strings, use Railway's **"Reference"** picker:
   ```
   SPRING_DATASOURCE_URL       = jdbc:${{Postgres.DATABASE_URL}}
   SPRING_DATASOURCE_USERNAME  = ${{Postgres.PGUSER}}
   SPRING_DATASOURCE_PASSWORD  = ${{Postgres.PGPASSWORD}}
   SPRING_DATA_REDIS_URL       = ${{Redis.REDIS_URL}}
   SPRING_RABBITMQ_HOST        = ${{RabbitMQ.RABBITMQ_HOST}}
   SPRING_RABBITMQ_PORT        = ${{RabbitMQ.RABBITMQ_PORT}}
   SPRING_RABBITMQ_USERNAME    = ${{RabbitMQ.RABBITMQ_USER}}
   SPRING_RABBITMQ_PASSWORD    = ${{RabbitMQ.RABBITMQ_PASSWORD}}
   ```
4. Generate the two app secrets and paste them in too:
   ```bash
   openssl rand -base64 64    # → APP_JWT_SECRET
   openssl rand -hex 24       # → IRC_VERIFICATION_SECRET
   ```

---

## 2. Deploy

```bash
git push origin main
```

That's it. Railway picks up the push, Nixpacks builds the project with JDK 21 (pinned by `nixpacks.toml`), starts the jar, and routes traffic once `/actuator/health` returns 200.

---

## 3. Local development

Nothing changes — keep the docker-compose stack running and:

```bash
docker compose up -d         # postgres, redis, rabbit, minio
./mvnw spring-boot:run
```

The localhost defaults in `application.yaml` already match the docker-compose service names/ports.
