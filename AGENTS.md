AGENTS.md — Quick guide for AI coding agents working on this repo

Purpose
- Give an AI agent the minimum, high-value knowledge to be immediately productive in this Spring Boot backend (IRC platform).

Big picture / architecture (what to know first)
- It's a single Spring Boot application (root class: `ak.dev.irc.IrcApplication`).
- Major bounded contexts are under `src/main/java/ak/dev/irc/app/`:
  - `research/` — domain logic for research CRUD, uploads, reactions, comments (services, controllers, repos, dtos).
  - `user/` — identity and user-related flows.
  - `security/` — JWT + OAuth2 helpers and utilities (see `SecurityConfig.java`).
  - `rabbitmq/` — event infrastructure (exchanges, queues, converters, listeners, publishers).
  - `config/` and `common/` — shared wiring (auditing, Jackson, web/CORS).
- Data flow highlights:
  - HTTP API → controller -> service -> repository (JPA) for CRUD.
  - Domain events published to RabbitMQ via `RabbitTemplate` using topic exchange `irc.topic.exchange` and routing keys in `RabbitMQConstants`.
  - Incoming RabbitMQ messages are JSON with a `__TypeId__` header — `Jackson2JsonMessageConverter` + trusted packages configured in `RabbitMQConfig.java`.
  - File uploads use Cloudflare R2 (configured under `app.storage.*` in `application.yaml`).

Developer workflows (commands & env)
- Build / run / test (use the Maven wrapper in project root):
  - Build: `./mvnw -DskipTests package` or `./mvnw package`
  - Run locally: `./mvnw spring-boot:run`
  - Tests: `./mvnw test`
- Environment: `.env` files are supported (library `spring-dotenv`). Key env vars used in `src/main/resources/application.yaml`:
  - Database: `DB_USERNAME`, `DB_PASSWORD` (jdbc URL default points to `jdbc:postgresql://localhost:5432/irc`).
  - RabbitMQ: `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`.
  - Cloud storage (R2): `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`.
  - JWT secret: `APP_JWT_SECRET`.
- Local dev defaults are permissive: CORS allows localhost origins, security is currently configured to `permitAll` (see `SecurityConfig.java`) and JPA `ddl-auto` is `create-drop` (see `application.yaml`) — expect an ephemeral DB state.

Project-specific conventions & notable patterns
- Package-by-feature layout: group controllers, services, repositories, dto, entity under each feature package (e.g., `app/research`). Use those folders when adding new code.
- Service interfaces are defined separately from implementations (e.g., `ResearchService` interface). Look for `service` packages to find both interface and `impl` classes.
- DTO-heavy API: request/response DTOs live under `dto` packages. Mappers exist under `mapper/`.
- Centralized constants for infra names: `RabbitMQConstants.java` holds all exchange/queue/routing-key strings — update here when changing topology.
- Audit model: entities extend `BaseAuditEntity` which captures created/updated metadata and uses `AuditorAware` (`AuditorAwareImpl`) that reads from `SecurityUtils`.
  - `BaseAuditEntity` attempts to capture IP & User-Agent from `RequestContextHolder` in JPA lifecycle hooks; it silently skips if no HTTP context (useful for background jobs/tests).
- RabbitMQ message conversion: `Jackson2JsonMessageConverter` is configured to rely on `__TypeId__` header and `DefaultJackson2JavaTypeMapper` trusted packages — check `RabbitMQConfig.messageConverter()`.
  - If adding new event classes, ensure they fall under the trusted packages or update this mapper.
- Jackson: custom `ObjectMapper` provided in `JacksonConfig.java` (registers JavaTimeModule and disables timestamps).
- Logging/telemetry: `application.yaml` sets package-specific levels (e.g., `ak.dev.irc` DEBUG). Change there for focused debugging.

Integration points & external dependencies
- Postgres (JDBC) — Spring Data JPA is used. Repositories are standard Spring Data interfaces under `repository/`.
- Redis — used for caching (Spring Cache + Redis). Configured via `spring.data.redis.*` in `application.yaml`.
- RabbitMQ — AMQP (exchanges/queues/bindings configured in `RabbitMQConfig.java`). Message retry + DLX behavior is configured in the same file.
- Cloudflare R2 (S3-compatible) — used for media uploads (endpoint & credentials in `application.yaml`). AWS SDK v2 BOM is imported in `pom.xml` and `s3` client is present.
- OAuth2 — Google client defaults are in `application.yaml` under `spring.security.oauth2.client` — OAuth flows are wired in `SecurityConfig`.

Quick places to look when making changes (examples)
- Entry point: `src/main/java/ak/dev/irc/IrcApplication.java`.
- Web/CORS: `src/main/java/ak/dev/irc/app/config/WebConfig.java` and `SecurityConfig.java` (CORS + security filters).
- RabbitMQ topology & converters: `src/main/java/ak/dev/irc/app/rabbitmq/config/RabbitMQConfig.java` and `.../constants/RabbitMQConstants.java`.
- Auditing: `src/main/java/ak/dev/irc/app/common/BaseAuditEntity.java`, `AuditConfig.java`, `AuditorAwareImpl.java`.
- DTOs & services: e.g. `src/main/java/ak/dev/irc/app/research/service/ResearchService.java` and related `controller/`, `dto/`, `repository/`.

Pitfalls & gotchas for agents
- Dev defaults are insecure/ephemeral: code assumes `create-drop` DB, permissive CORS and `permitAll`. Don't assume production-safe settings; check `application.yaml` before proposing security changes.
- Message converter trusted packages: the `RabbitMQConfig` lists trusted packages; if your agent adds new event classes under a different package, messages may fail to deserialize unless you update trusted packages.
- Logging points: many components log at DEBUG under `ak.dev.irc` — use that package prefix to find debug logs.
- Java version: `pom.xml` sets `<java.version>25</java.version>` — ensure code or build steps respect that (CI/containers may differ).

How to be helpful as an AI agent (concrete next steps)
- When modifying domain behavior, update DTOs + mappers and add integration tests under `src/test/java/ak/dev/irc`.
- When changing RabbitMQ or storage behavior, update `RabbitMQConstants.java` and `application.yaml` defaults together.
- For auth changes, review `SecurityConfig.java`, `Jwt*` classes and `SecurityUtils` for current assumptions.

Contact points / files referenced
- IrcApplication.java
- application.yaml (src/main/resources/application.yaml)
- src/main/java/ak/dev/irc/app/config/SecurityConfig.java
- src/main/java/ak/dev/irc/app/rabbitmq/config/RabbitMQConfig.java
- src/main/java/ak/dev/irc/app/rabbitmq/constants/RabbitMQConstants.java
- src/main/java/ak/dev/irc/app/common/BaseAuditEntity.java
- src/main/java/ak/dev/irc/app/research/service/ResearchService.java

EOF

