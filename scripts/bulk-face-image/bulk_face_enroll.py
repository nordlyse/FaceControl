#!/usr/bin/env python3
"""
Bulk upsert face enrollment JPEGs into deepface.face_enrollment.

The app does not store profile photos on user.users — you must supply image files
(a manifest CSV listing email + path per user).

Usage:
  pip install -r scripts/bulk-face-image/requirements-bulk-enroll.txt
  python scripts/bulk-face-image/bulk_face_enroll.py \\
    --dsn postgresql://postgres:PASSWORD@localhost:1001/app \\
    --manifest scripts/bulk-face-image/bulk-face-enroll-manifest.example.csv \\
    --dry-run

Then run without --dry-run to commit.

After images are loaded, optionally sync Keycloak UUIDs:
  docker compose exec -T postgres psql -U postgres -d app < scripts/bulk-face-image/sync-face-enrollment-keycloak-ids.sql
(Repo root; `-f` would look inside the container where this file is not mounted.)
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path


def _normalize_header(name: str) -> str:
    return name.strip().lower().replace("-", "_")


def load_manifest(path: Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if not reader.fieldnames:
            raise SystemExit("Manifest CSV has no header row")
        fieldmap = {_normalize_header(h): h for h in reader.fieldnames}

        def col(*names: str) -> str | None:
            for n in names:
                key = _normalize_header(n)
                if key in fieldmap:
                    return fieldmap[key]
            return None

        c_email = col("email", "user_email")
        c_path = col("image_path", "path", "file", "jpeg", "image")
        c_kc = col("keycloak_user_id", "keycloak_id", "kc_id")
        if not c_email or not c_path:
            raise SystemExit(
                "Manifest must include columns: email + image_path (or path/file/jpeg/image)"
            )

        for raw in reader:
            email = (raw.get(c_email) or "").strip()
            img_path = (raw.get(c_path) or "").strip()
            kc = (raw.get(c_kc) or "").strip() if c_kc else ""
            if not email or email.startswith("#"):
                continue
            if not img_path:
                print(f"skip (empty path): {email}", file=sys.stderr)
                continue
            rows.append({"email": email, "image_path": img_path, "keycloak_user_id": kc})
    return rows


UPSERT_SQL = """
INSERT INTO deepface.face_enrollment (
    user_id,
    enrolled_by_email,
    reference_image,
    keycloak_user_id,
    model_name,
    detector_backend,
    active
)
VALUES (%s, %s, %s, %s, 'Facenet', 'opencv', true)
ON CONFLICT (user_id) DO UPDATE SET
    reference_image    = EXCLUDED.reference_image,
    enrolled_by_email  = COALESCE(EXCLUDED.enrolled_by_email, deepface.face_enrollment.enrolled_by_email),
    keycloak_user_id   = COALESCE(NULLIF(EXCLUDED.keycloak_user_id, ''),
                                 deepface.face_enrollment.keycloak_user_id),
    updated_at         = now(),
    active             = true;
"""


def main() -> None:
    p = argparse.ArgumentParser(description="Bulk enroll JPEG faces into deepface.face_enrollment")
    p.add_argument("--dsn", required=True, help="PostgreSQL DSN, e.g. postgresql://postgres:pw@127.0.0.1:1001/app")
    p.add_argument("--manifest", required=True, type=Path, help="CSV with email + image_path columns")
    p.add_argument("--dry-run", action="store_true", help="Validate files and rows only; no DB writes")
    args = p.parse_args()

    rows = load_manifest(args.manifest)
    if not rows:
        raise SystemExit("No data rows in manifest")

    errors = 0
    for r in rows:
        img = Path(r["image_path"]).expanduser()
        if not img.is_file():
            print(f"ERROR missing file for {r['email']}: {img}", file=sys.stderr)
            errors += 1
            continue
        data = img.read_bytes()
        if len(data) <= 100:
            print(f"ERROR image too small for {r['email']} (need >100 bytes): {img}", file=sys.stderr)
            errors += 1
            continue
        r["_bytes"] = data
        r["_path_resolved"] = str(img.resolve())

    if errors:
        raise SystemExit(f"Fix {errors} manifest/path errors before continuing")

    if args.dry_run:
        print(f"Dry-run OK: {len(rows)} users ready to upsert")
        for r in rows:
            print(f"  - {r['email']} <- {r['_path_resolved']} ({len(r['_bytes'])} bytes)")
        return

    try:
        import psycopg
    except ImportError:
        raise SystemExit(
            "Install deps: pip install -r scripts/bulk-face-image/requirements-bulk-enroll.txt"
        ) from None

    with psycopg.connect(args.dsn) as conn:
        with conn.cursor() as cur:
            for r in rows:
                cur.execute(
                    'SELECT id FROM "user".users WHERE lower(trim(email)) = lower(trim(%s))',
                    (r["email"],),
                )
                uid = cur.fetchone()
                if not uid:
                    print(f"ERROR no user.users row for email {r['email']}", file=sys.stderr)
                    conn.rollback()
                    raise SystemExit(1)
                user_id = uid[0]
                kc = r["keycloak_user_id"] or None
                cur.execute(
                    UPSERT_SQL,
                    (user_id, r["email"], r["_bytes"], kc),
                )
                print(f"upserted {r['email']} -> user_id={user_id}")
        conn.commit()
    print("Done.")


if __name__ == "__main__":
    main()
