# CPU-only DeepFace worker (Facenet + opencv). Pre-downloads weights at build time.
FROM python:3.11-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    TF_CPP_MIN_LOG_LEVEL=2 \
    CUDA_VISIBLE_DEVICES=-1

RUN apt-get update && apt-get install -y --no-install-recommends \
    libglib2.0-0 libsm6 libxext6 libxrender1 \
    libgomp1 ffmpeg curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --upgrade pip && pip install -r requirements.txt \
 && mkdir -p /root/.deepface/weights \
 && curl -fSL -o /root/.deepface/weights/facenet_weights.h5 \
      https://github.com/serengil/deepface_models/releases/download/v1.0/facenet_weights.h5

COPY app ./app
EXPOSE 8054

HEALTHCHECK --interval=15s --timeout=20s --retries=20 --start-period=240s \
    CMD curl -sf http://127.0.0.1:8054/health >/dev/null || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8054"]
