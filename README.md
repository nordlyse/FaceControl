# FaceControl

FaceControl is a **Keycloak face-login stack**: after username and password, the user captures a live camera frame and DeepFace compares it to an enrolled JPEG (`face-verify` SPI, `face-auth-bridge`, `deepface-worker-rs`).

Camera capture happens in the Keycloak login page (`face-verify.ftl`). There is no file-upload path.

## Architecture

Every runtime piece in this repo is on the login path. There is no unused application container.

| Piece | Language | How it runs | Used? |
|-------|----------|-------------|-------|
| `keycloak` | Keycloak 23 (Quarkus) | Compose service `:8080` | Yes — username/password + face form |
| `keycloak-deepface-provider` | Java SPI | Built **into** the Keycloak image (`kc.sh build`), not a separate process | Yes — authenticator `face-verify` + `face-verify.ftl` |
| `face-auth-bridge` | Java (Spring Boot) | Compose service `:8071` | Yes — enrollment DB + worker client |
| `deepface-worker-rs` | Python (FastAPI / DeepFace) | Compose service `:8054` | Yes — Facenet compare of two JPEGs |
| `postgres` | PostgreSQL 17 | Compose service `:1001` | Yes — Keycloak schema + `"user".users` + `deepface.face_enrollment` |
| `scripts/init-db.sql` | SQL | Mounted into Postgres on first empty volume | Yes |
| `scripts/bulk-face-image/` | Python / SQL | Run by an operator when needed | Optional — bulk JPEG enroll |
| `configs/traefik/dynamic/keycloak-large-post.yml` | Traefik | **Not** started by `docker-compose.yml` | Optional — only if Traefik sits in front of Keycloak |

```mermaid
flowchart TB
  subgraph clients [Clients]
    Browser["Browser / OIDC app"]
  end

  subgraph compose [docker-compose.yml — all four services are used]
    KC["Keycloak 23<br/>includes keycloak-deepface-provider JAR<br/>:8080 /auth"]
    Bridge["face-auth-bridge<br/>Java Spring Boot<br/>:8071"]
    Worker["deepface-worker-rs<br/>Python FastAPI + DeepFace<br/>:8054"]
    PG[("PostgreSQL<br/>database app")]
  end

  Browser -->|"1. username / password"| KC
  KC -->|"2. camera JPEG face_image"| KC
  KC -->|"3. POST /internal/api/v1/verify<br/>X-Internal-Face-Secret"| Bridge
  Bridge -->|"4. reference JPEG"| PG
  Bridge -->|"5. multipart reference + probe"| Worker
  Worker -->|"6. verified / distance / threshold"| Bridge
  Bridge -->|"7. HTTP 200 / 403 / 404"| KC
  KC -->|"8. login success or MFA failure"| Browser
  KC -.->|"stores users, sessions, realm app"| PG
```

Login sequence:

```mermaid
sequenceDiagram
  actor User
  participant Browser
  participant Keycloak as Keycloak + face-verify SPI
  participant Bridge as face-auth-bridge
  participant DB as PostgreSQL
  participant Worker as deepface-worker-rs

  User->>Browser: Open app login
  Browser->>Keycloak: OIDC authorize
  User->>Keycloak: Username + password
  Keycloak->>Browser: Face verification page
  User->>Browser: Start camera, capture
  Browser->>Keycloak: POST face_image (JPEG base64)

  alt First login and FACE_SELF_ENROLL_ON_FIRST_LOGIN=true
    Keycloak->>Bridge: POST /internal/api/v1/enrollment-status
    Bridge->>DB: Lookup enrollment
    Keycloak->>Bridge: POST /internal/api/v1/enroll
    Bridge->>DB: Store reference JPEG
    Keycloak->>Browser: Login success
  else Later login / verify
    Keycloak->>Bridge: POST /internal/api/v1/verify
    Bridge->>DB: Load reference JPEG
    Bridge->>Worker: POST /verify multipart
    Worker-->>Bridge: verified true or false
    Bridge-->>Keycloak: 200 / 403 / 404
    Keycloak->>Browser: Login success or retry
  end
```

**Self-enrollment (optional):** if `FACE_SELF_ENROLL_ON_FIRST_LOGIN=true`, the first successful capture is stored via `POST /internal/api/v1/enroll`. Later logins use `/verify`. Self-enroll requires a matching row in `"user".users` (same email as the Keycloak user).

**Silent OIDC:** if the client sends `prompt=none`, the face step is skipped so iframe token refresh is not broken.

## Repository layout

| Path | Role |
|------|------|
| `keycloak-deepface-provider/` | Keycloak 23 authenticator SPI. Provider id **`face-verify`**. Theme: `src/main/resources/theme-resources/templates/face-verify.ftl`. |
| `face-auth-bridge/` | Spring Boot internal REST API (`8071`). Resolves enrollment, calls the worker. |
| `deepface-worker-rs/` | FastAPI `GET /health`, `POST /verify` (multipart JPEG). |
| `keycloak/Dockerfile` | Builds the SPI JAR and runs `kc.sh build`. Image tag in Compose: `facecontrol-keycloak:23-deepface`. |
| `keycloak/app-realm.json` | Demo realm **`app`** with extended OAuth code lifespans (`accessCodeLifespan` / `accessCodeLifespanLogin` = **1800** s) so slow MFA does not expire `session_code`. |
| `keycloak/docker-entrypoint.sh` | `kc.sh start-dev --import-realm`. |
| `scripts/init-db.sql` | `"user".users` + `deepface.face_enrollment` (runs on an **empty** Postgres volume). |
| `scripts/bulk-face-image/` | CSV JPEG enrollment + optional Keycloak UUID sync. |
| `configs/traefik/dynamic/keycloak-large-post.yml` | Traefik buffering (~64 MiB) so large face POSTs are not rejected with **413**. |
| `docker-compose.yml` | Postgres, worker, bridge, Keycloak. |

## Quick start (this repository)

Requirements: Docker Compose, several GB of disk (TensorFlow + Facenet weights are downloaded at **image build** time).

```bash
cp .env.example .env
# Change FACE_BRIDGE_SECRET and KEYCLOAK_ADMIN_PASSWORD before any production use.

docker compose build
docker compose up -d
```

Wait until Keycloak is healthy (`docker compose ps`). The DeepFace worker healthcheck allows up to ~4 minutes on first start.

| URL | Purpose |
|-----|---------|
| http://localhost:8080/auth | Keycloak (HTTP relative path `/auth`) |
| http://localhost:8080/auth/admin | Admin console — `admin` / `admin` (or `.env`) |
| http://localhost:8080/auth/realms/app | Realm `app` |
| Postgres `localhost:1001` | Database `app` / user `postgres` |

Demo Keycloak users (imported from `app-realm.json`):

| Username | Email | Password |
|----------|-------|----------|
| `demo` | `demo@app.local` | `Demo@123` |
| `tester` | `tester@app.local` | `Tester@123` |

Matching `"user".users` rows are created by `scripts/init-db.sql` so first-login self-enroll works.

### Add the face step to the Browser flow

Realm import does **not** attach the authenticator. Do this once in Admin Console:

1. Open http://localhost:8080/auth/admin → realm **app**.
2. **Authentication → Flows**.
3. **Duplicate** the built-in **Browser** flow (for example `browser with face`).
4. **Add execution** → **Face verification** (provider id `face-verify`).
5. Place it **immediately after Username Password Form**.
6. Set requirement to **Required**.
7. **Authentication → Bindings**: set **Browser flow** to the duplicated flow and save.

Do **not** put a Required face step at the same level as Alternative cookie / identity-provider executions on the Browser root. Keep face verification inside the forms sub-flow (same nesting as the default Browser flow). Otherwise Keycloak may ignore cookie SSO (`REQUIRED and ALTERNATIVE elements at the same level`).

Camera access needs a **secure context**: `https://` or `http://localhost`. `http://127.0.0.1` is usually fine; a LAN IP over plain HTTP will block `getUserMedia`.

Try login: http://localhost:8080/auth/realms/app/account (or any OIDC client). After password, start the camera, capture, then Continue.

## Integrate with an existing Keycloak

Use this when Keycloak already runs in another Compose file or cluster. You still need **Postgres tables**, **face-auth-bridge**, **deepface-worker-rs**, and the **SPI JAR** inside Keycloak.

### 1. Database

On the database Keycloak / the bridge can reach, apply the DeepFace DDL (see `scripts/init-db.sql`):

- Schema `"user"` with table `users` (`id UUID`, `email UNIQUE`).
- Schema `deepface` with table `face_enrollment` (FK `user_id → "user".users(id)`, `reference_image BYTEA`, optional `keycloak_user_id`, `enrolled_by_email`).

Every identity that should enroll or verify must have:

1. A Keycloak user **with an email**.
2. A `"user".users` row whose `email` matches (required for self-enroll and typical lookups).
3. An enrollment JPEG (self-enroll, bulk script, or `INSERT`).

If you already have an application user table, either:

- Keep using `"user".users` as in this repo, **or**
- Point `FaceEnrollmentRepository` at your table (same email lookup).

### 2. Run worker + bridge on the Keycloak Docker network

```yaml
deepface-worker-rs:
  build: ./deepface-worker-rs
  environment:
    CUDA_VISIBLE_DEVICES: "-1"
  tmpfs:
    - /tmp:rw,size=512m,mode=1777

face-auth-bridge:
  build: ./face-auth-bridge
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://YOUR_POSTGRES:5432/YOUR_DB
    SPRING_DATASOURCE_USERNAME: ...
    SPRING_DATASOURCE_PASSWORD: ...
    FACE_WORKER_URL: http://deepface-worker-rs:8054
    FACE_BRIDGE_SECRET: ${FACE_BRIDGE_SECRET}
  depends_on:
    deepface-worker-rs:
      condition: service_healthy
```

Do **not** publish `/internal` on the public internet. Keep the bridge on an internal Docker/K8s network.

### 3. Build Keycloak with the provider JAR

This repo’s `keycloak/Dockerfile`:

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /w
COPY keycloak-deepface-provider/pom.xml keycloak-deepface-provider/
COPY keycloak-deepface-provider/src keycloak-deepface-provider/src/
RUN mvn -q -f keycloak-deepface-provider/pom.xml -DskipTests package

FROM quay.io/keycloak/keycloak:23.0
USER root
COPY --from=build /w/keycloak-deepface-provider/target/keycloak-deepface-provider-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
USER 1000
```

The FreeMarker template **must** live at `theme-resources/templates/face-verify.ftl` inside the JAR (Keycloak 23 classpath theme resources). A wrong path yields **template not found**.

SPI registration: `META-INF/services/org.keycloak.authentication.AuthenticatorFactory` → `com.facecontrol.keycloak.face.FaceAuthenticatorFactory`.

Keycloak **23.x** is the tested version (`keycloak.version` 23.0.7 in the provider POM). Newer Keycloak SPI packages may require code changes.

### 4. Keycloak environment variables

| Variable | Effect |
|----------|--------|
| `FACE_BRIDGE_URL` | Bridge base URL, e.g. `http://face-auth-bridge:8071` |
| `FACE_BRIDGE_SECRET` | Must match `FACE_BRIDGE_SECRET` on the bridge (`X-Internal-Face-Secret`) |
| `FACE_DISABLED` | `true` → skip the face step entirely |
| `FACE_OPTIONAL_NO_ENROLL` | `true` → HTTP 404 from the bridge counts as login success (no enrollment) |
| `FACE_SELF_ENROLL_ON_FIRST_LOGIN` | `true` → first capture stored via `/enroll` (Compose default for this repo) |
| `FACE_BRIDGE_TIMEOUT_MS` | HTTP timeout to the bridge (default **45000**) |

Raise body limits so the base64 JPEG POST is not dropped:

```yaml
QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE: 64M
JAVA_OPTS_APPEND: "-Dquarkus.http.limits.max-body-size=64M"
```

If Traefik sits in front of Keycloak, attach `configs/traefik/dynamic/keycloak-large-post.yml` (`keycloak-large-post` buffering ~64 MiB). Other proxies need an equivalent request-body limit.

### 5. Realm tokens (long MFA)

DeepFace cold start can exceed the default login `session_code` lifetime. In Admin Console: **Realm settings → Tokens**, set **Login timeout** / access-code lifespans to **1800** seconds (already in `app-realm.json`). Re-importing JSON often **does not** overwrite an existing realm — change Tokens in the UI if you still see `invalid_code`.

### 6. Authentication flow

Same Admin Console steps as [Add the face step to the Browser flow](#add-the-face-step-to-the-browser-flow). Bind the duplicated flow as the realm **Browser** flow.

### 7. Application OIDC client

Create (or reuse) a client, for example `app`:

- Protocol: OpenID Connect
- Access type: public (SPA) or confidential (server)
- Valid redirect URIs: your app callbacks (`http://localhost:3000/*`, production HTTPS URLs)
- Web origins: matching origins
- Standard flow: on
- PKCE: S256 for public clients

Issuer for this Compose stack:

```
http://localhost:8080/auth/realms/app
```

Well-known config:

```
http://localhost:8080/auth/realms/app/.well-known/openid-configuration
```

Point your app’s OIDC library at that issuer. Face verification is **inside Keycloak**; the app only starts the authorization code flow as usual.

## Enrollment

The bridge looks up the reference JPEG by:

1. Keycloak user **email** against `"user".users.email` and `deepface.face_enrollment.enrolled_by_email`
2. Fallback: `deepface.face_enrollment.keycloak_user_id`

### Option A — First-login camera enroll (development)

Set `FACE_SELF_ENROLL_ON_FIRST_LOGIN=true` on Keycloak. Prerequisite: `"user".users` row with the same email.

### Option B — Bulk JPEG

```bash
pip install -r scripts/bulk-face-image/requirements-bulk-enroll.txt
python scripts/bulk-face-image/bulk_face_enroll.py \
  --dsn postgresql://postgres:PASSWORD@localhost:1001/app \
  --manifest scripts/bulk-face-image/bulk-face-enroll-manifest.example.csv \
  --dry-run
# then rerun without --dry-run
```

Optional Keycloak UUID sync (host stdin; realm name `app` in the SQL):

```bash
docker compose exec -T postgres psql -U postgres -d app < scripts/bulk-face-image/sync-face-enrollment-keycloak-ids.sql
```

## Bridge API (internal)

All `/internal/**` routes require header `X-Internal-Face-Secret`.

| Method | Path | Body | Notes |
|--------|------|------|--------|
| POST | `/internal/api/v1/verify` | `email`, `username`, `keycloakUserId`, `faceImageBase64` | **403** mismatch, **404** no enrollment |
| POST | `/internal/api/v1/enrollment-status` | `email`, `username`, `keycloakUserId` | `{ "enrolled": true\|false }` |
| POST | `/internal/api/v1/enroll` | same as verify | **404** if no `"user".users` row |

Worker: `GET /health`, `POST /verify` multipart fields `reference` and `probe`.

### Bridge flags

| Variable | Effect |
|----------|--------|
| `FACE_WORKER_URL` | `http://deepface-worker-rs:8054` |
| `FACE_BRIDGE_SECRET` | Internal API secret |
| `FACE_REQUIRE_ENROLLMENT` | `false` → missing enrollment treated as verified (use with care) |

## Troubleshooting

| Symptom | Typical cause |
|---------|----------------|
| **413** on login POST | Proxy / Keycloak body limit — Traefik `keycloak-large-post.yml` + Quarkus `max-body-size` |
| **Template not found** `face-verify.ftl` | Template not under `theme-resources/templates/` in the provider JAR |
| **Face enrollment is required** | No `deepface.face_enrollment` row or email mismatch; **404** from bridge |
| **No application user matches this account email** | Missing `"user".users` row for that email (self-enroll) |
| **Face verification service is unavailable** | Bridge down, DB error (**503**), worker not healthy, secret mismatch |
| **Bridge HTTP 500** `NoClassDefFoundError: Publisher` | Missing `reactive-streams` on the bridge classpath (already in `pom.xml`) |
| **Worker HTTP 422** missing reference/probe | Multipart encoding; bridge uses `RestTemplate` for FastAPI |
| Worker unhealthy on startup | Missing `tf-keras`; first TensorFlow load — wait for `start_period` 240s |
| CUDA / `cuInit` in logs | Harmless on CPU; `CUDA_VISIBLE_DEVICES=-1` |
| `invalid_code` / login again | Login session expired during slow MFA — prefetch weights (Dockerfile), 1800s code lifespan, one browser tab |
| Camera blocked | Not a secure context; user denied permission |
| **No space left on device** (`/tmp`) | Host Docker disk full; Compose `tmpfs /tmp` mitigates inside containers |

## Security

- Rotate `FACE_BRIDGE_SECRET` and Keycloak admin passwords; the Compose defaults are **development only**.
- Never expose `face-auth-bridge` `/internal` publicly.
- Face images are biometric data: restrict database access, backups, and logs.
- Self-enroll on first login is convenient for demos; production often uses admin/bulk enrollment and `FACE_SELF_ENROLL_ON_FIRST_LOGIN=false`.

## License note

Keycloak SPI dependencies are Apache-2.0. DeepFace / TensorFlow have their own licenses.
