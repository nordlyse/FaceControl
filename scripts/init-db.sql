-- FaceControl bootstrap (empty Postgres volume only).
-- Application users live in "user".users; biometric JPEG bytes in deepface.face_enrollment.
-- Keycloak stores its own tables in schema keycloak (KC_DB_SCHEMA).

CREATE SCHEMA IF NOT EXISTS keycloak;
CREATE SCHEMA IF NOT EXISTS "user";
CREATE SCHEMA IF NOT EXISTS deepface;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS "user".users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    full_name VARCHAR(255),
    is_active BOOLEAN DEFAULT true,
    inserted_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- --- DeepFace / face MFA ---
CREATE TABLE IF NOT EXISTS deepface.face_enrollment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES "user".users(id) ON DELETE CASCADE,
    keycloak_user_id VARCHAR(64),
    active BOOLEAN NOT NULL DEFAULT true,
    enrolled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    enrolled_by_email VARCHAR(255),
    model_name VARCHAR(80) NOT NULL DEFAULT 'Facenet',
    detector_backend VARCHAR(80) NOT NULL DEFAULT 'opencv',
    reference_image BYTEA NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_face_positive_length CHECK (length(reference_image) > 100)
);

CREATE INDEX IF NOT EXISTS idx_deepface_face_keycloak ON deepface.face_enrollment (keycloak_user_id);
CREATE INDEX IF NOT EXISTS idx_deepface_face_email ON deepface.face_enrollment (enrolled_by_email);

COMMENT ON SCHEMA deepface IS 'DeepFace biometric data; consumed by face-auth-bridge / Keycloak MFA step.';
COMMENT ON TABLE deepface.face_enrollment IS 'Enrollment photo (JPEG bytes). Face matching via deepface-worker-rs (Python/DeepFace).';

-- Demo identities. Self-enroll and bulk enroll require a matching email in this table
-- AND a Keycloak user with the same email.
INSERT INTO "user".users (email, full_name)
VALUES
    ('demo@facecontrol.local', 'Demo User'),
    ('tester@primeapp.com', 'Tester')
ON CONFLICT (email) DO NOTHING;
