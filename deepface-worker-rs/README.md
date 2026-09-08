# deepface-worker-rs

Face verification worker for Keycloak MFA (port **8054**).

Same API as the legacy `deepface-worker` service: **`GET /health`**, **`POST /verify`** (multipart `reference` + `probe` JPEG). Uses **DeepFace** Facenet + opencv detector so results match `face-auth-bridge` expectations.

## Run

```bash
docker compose build deepface-worker-rs
docker compose up -d deepface-worker-rs face-auth-bridge
```

## Environment

| Variable | Default |
|----------|---------|
| `CUDA_VISIBLE_DEVICES` | `-1` (CPU only) |

First startup can take several minutes while TensorFlow loads; healthcheck `start_period` is 240s.
