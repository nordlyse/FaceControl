"""Minimal DeepFace verify API — Java face-auth-bridge sends reference + probe JPEG bytes."""

import logging
import math
import tempfile
from pathlib import Path

from deepface import DeepFace
from fastapi import FastAPI, File, HTTPException, UploadFile

MODEL = "Facenet"
DETECTOR = "opencv"

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

app = FastAPI(title="deepface-worker-rs", version="1.0")


def _json_safe_float(value) -> float | None:
    """RFC-compliant JSON cannot represent NaN/Inf; omit them so Java Jackson can parse and serialize."""
    try:
        x = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(x) or math.isinf(x):
        return None
    return x


def _persist_upload(data: bytes) -> Path:
    if not data or len(data) < 200:
        raise HTTPException(status_code=400, detail="Image too small or empty")
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".jpg")
    try:
        tmp.write(data)
        tmp.flush()
        return Path(tmp.name)
    finally:
        tmp.close()


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/verify")
async def verify(
    reference: UploadFile = File(..., description="Enrolled JPEG from DB"),
    probe: UploadFile = File(..., description="JPEG from live capture"),
):
    """Returns DeepFace.verify result."""
    ref_path = probe_path = None
    try:
        ref_data = await reference.read()
        probe_data = await probe.read()
        ref_path = _persist_upload(ref_data)
        probe_path = _persist_upload(probe_data)
        result = DeepFace.verify(
            img1_path=str(ref_path),
            img2_path=str(probe_path),
            enforce_detection=False,
            model_name=MODEL,
            detector_backend=DETECTOR,
        )
        verified = bool(result.get("verified", False))
        distance = _json_safe_float(result.get("distance", 0))
        thresh = _json_safe_float(result.get("threshold", 0))
        return {"verified": verified, "distance": distance, "threshold": thresh, "model": MODEL}
    except HTTPException:
        raise
    except Exception as e:
        log.exception("DeepFace.verify failed")
        raise HTTPException(status_code=422, detail=str(e)) from e
    finally:
        for p in (ref_path, probe_path):
            if p is not None:
                try:
                    p.unlink(missing_ok=True)
                except Exception:
                    pass
