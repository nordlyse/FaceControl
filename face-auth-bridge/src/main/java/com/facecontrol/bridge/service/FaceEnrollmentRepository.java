package com.facecontrol.bridge.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class FaceEnrollmentRepository {

    private static final String UPSERT_ENROLLMENT =
            """
            INSERT INTO deepface.face_enrollment (
                user_id,
                enrolled_by_email,
                reference_image,
                keycloak_user_id,
                model_name,
                detector_backend,
                active
            )
            VALUES (?, ?, ?, ?, 'Facenet', 'opencv', true)
            ON CONFLICT (user_id) DO UPDATE SET
                reference_image    = EXCLUDED.reference_image,
                enrolled_by_email  = COALESCE(EXCLUDED.enrolled_by_email, deepface.face_enrollment.enrolled_by_email),
                keycloak_user_id   = COALESCE(NULLIF(EXCLUDED.keycloak_user_id, ''),
                                             deepface.face_enrollment.keycloak_user_id),
                updated_at         = now(),
                active             = true
            """;

    private final JdbcTemplate jdbc;

    public FaceEnrollmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> findUserIdByEmail(String emailOrUsernameLookup) {
        if (emailOrUsernameLookup == null || emailOrUsernameLookup.isBlank()) {
            return Optional.empty();
        }
        String sql =
                """
                SELECT id FROM "user".users
                WHERE lower(trim(email)) = lower(trim(?))
                LIMIT 1
                """;
        try {
            UUID id = jdbc.queryForObject(sql, (rs, rowNum) -> rs.getObject("id", UUID.class), emailOrUsernameLookup);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void upsertEnrollment(UUID userId, String enrolledByEmail, byte[] referenceImage, String keycloakUserId) {
        String kc = (keycloakUserId != null && !keycloakUserId.isBlank()) ? keycloakUserId.strip() : null;
        jdbc.update(UPSERT_ENROLLMENT, userId, enrolledByEmail, referenceImage, kc);
    }

    public Optional<byte[]> findReferenceBytesByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT fe.reference_image
                FROM deepface.face_enrollment fe
                INNER JOIN "user".users u ON u.id = fe.user_id
                WHERE fe.active IS TRUE
                  AND (
                    LOWER(trim(u.email)) = LOWER(trim(?))
                    OR LOWER(trim(COALESCE(fe.enrolled_by_email, ''))) = LOWER(trim(?))
                  )
                LIMIT 1
                """;
        try {
            byte[] bytes = jdbc.queryForObject(sql, (rs, rowNum) -> rs.getBytes("reference_image"), email, email);
            return Optional.ofNullable(bytes);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<byte[]> findReferenceBytesByKeycloakUserId(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            return Optional.empty();
        }
        String sql =
                """
                SELECT fe.reference_image
                FROM deepface.face_enrollment fe
                WHERE fe.active IS TRUE
                  AND trim(fe.keycloak_user_id) = trim(?)
                LIMIT 1
                """;
        try {
            byte[] bytes =
                    jdbc.queryForObject(sql, (rs, rowNum) -> rs.getBytes("reference_image"), keycloakUserId);
            return Optional.ofNullable(bytes);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
