-- Populate deepface.face_enrollment.keycloak_user_id from Keycloak DB by matching email.
-- Realm name defaults to 'prime'; adjust if needed.
-- Requires Keycloak using PostgreSQL with schema name below (see KC_DB_SCHEMA in docker-compose, usually keycloak).
--
-- Apply from host (repo root); do not use psql -f with a host path inside the container:
--   docker compose exec -T postgres psql -U postgres -d primeapp < scripts/bulk-face-image/sync-face-enrollment-keycloak-ids.sql

UPDATE deepface.face_enrollment fe
SET keycloak_user_id = ue.id::text,
    updated_at       = now()
FROM "user".users u
JOIN keycloak.user_entity ue
  ON lower(trim(ue.email)) = lower(trim(u.email))
JOIN keycloak.realm r
  ON r.id = ue.realm_id
 AND r.name = 'prime'
WHERE fe.user_id = u.id
  AND (fe.keycloak_user_id IS NULL OR trim(fe.keycloak_user_id) = '');
