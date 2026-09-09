package com.facecontrol.bridge.service;

import com.facecontrol.bridge.config.BridgeProperties;
import com.facecontrol.bridge.dto.VerifyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class FaceVerifyService {

    private static final Logger log = LoggerFactory.getLogger(FaceVerifyService.class);

    private final FaceEnrollmentRepository repo;
    private final DeepFaceWorkerClient worker;
    private final BridgeProperties props;

    public FaceVerifyService(FaceEnrollmentRepository repo, DeepFaceWorkerClient worker, BridgeProperties props) {
        this.repo = repo;
        this.worker = worker;
        this.props = props;
    }

    /**
     * Whether an active enrollment row exists for this identity (same resolution rules as verify).
     */
    public boolean hasEnrollment(String email, String username, String keycloakUserId) {
        validateIdentityPresent(email, username, keycloakUserId);
        try {
            return resolveEnrollmentReference(email, username, keycloakUserId).isPresent();
        } catch (DataAccessException e) {
            log.error(
                    "Face enrollment DB lookup failed — ensure scripts/init-db.sql ran (deepface.face_enrollment): {}",
                    e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "enrollment database error");
        }
    }

    /**
     * Store or replace reference JPEG from first-login capture. Requires a matching {@code "user".users} row
     * (same email/username lookup as verify).
     */
    public void enrollReference(String email, String username, String keycloakUserId, String faceImageBase64) {
        validateIdentityPresent(email, username, keycloakUserId);
        if (faceImageBase64 == null || faceImageBase64.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "faceImageBase64 required");
        }
        byte[] jpeg;
        try {
            jpeg = Base64.getDecoder().decode(faceImageBase64.strip());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid base64 face image");
        }
        if (jpeg.length <= 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "face image too small");
        }
        String lookup = email != null && !email.isBlank() ? email.strip() : username.strip();
        UUID userId;
        try {
            userId =
                    repo.findUserIdByEmail(lookup)
                            .orElseThrow(
                                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no application user"));
            String enrolledBy = (email != null && !email.isBlank()) ? email.strip() : lookup;
            String kc = (keycloakUserId != null && !keycloakUserId.isBlank()) ? keycloakUserId.strip() : null;
            repo.upsertEnrollment(userId, enrolledBy, jpeg, kc);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("Face enrollment upsert failed: {}", e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "enrollment database error");
        }
    }

    public VerifyResponse verify(String email, String username, String keycloakUserId, String faceImageBase64) {
        validateIdentityPresent(email, username, keycloakUserId);
        Optional<byte[]> refOpt;
        try {
            refOpt = resolveEnrollmentReference(email, username, keycloakUserId);
        } catch (DataAccessException e) {
            log.error(
                    "Face enrollment DB lookup failed — ensure scripts/init-db.sql ran (deepface.face_enrollment): {}",
                    e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "enrollment database error");
        }
        String lookup = email != null && !email.isBlank() ? email : username;
        boolean hasKeycloakId = keycloakUserId != null && !keycloakUserId.isBlank();
        if (refOpt.isEmpty()) {
            if (!props.isRequireEnrollment()) {
                log.debug(
                        "Skipping face MFA (no enrollment) lookup={} keycloakUserId={}",
                        lookup,
                        keycloakUserId);
                return new VerifyResponse(true, null, null, null, "no enrollment; skipped");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no face enrollment for user");
        }
        if (faceImageBase64 == null || faceImageBase64.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "faceImageBase64 required");
        }
        byte[] probe;
        try {
            probe = Base64.getDecoder().decode(faceImageBase64.strip());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid base64 face image");
        }
        try {
            var r = worker.verifyOrThrow(refOpt.get(), probe);
            if (!r.verified()) {
                return new VerifyResponse(false, r.distance(), r.threshold(), DeepFaceWorkerClient.MODEL_HINT, null);
            }
            return new VerifyResponse(true, r.distance(), r.threshold(), DeepFaceWorkerClient.MODEL_HINT, null);
        } catch (Exception e) {
            log.warn("verification failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "deepface verification failed");
        }
    }

    private static void validateIdentityPresent(String email, String username, String keycloakUserId) {
        boolean hasEmailOrUsername =
                (email != null && !email.isBlank()) || (username != null && !username.isBlank());
        boolean hasKeycloakId = keycloakUserId != null && !keycloakUserId.isBlank();
        if (!hasEmailOrUsername && !hasKeycloakId) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "email, username, or keycloakUserId required");
        }
    }

    private Optional<byte[]> resolveEnrollmentReference(String email, String username, String keycloakUserId) {
        boolean hasKeycloakId = keycloakUserId != null && !keycloakUserId.isBlank();
        String lookup = email != null && !email.isBlank() ? email : username;
        Optional<byte[]> refOpt = Optional.empty();
        if (lookup != null && !lookup.isBlank()) {
            refOpt = repo.findReferenceBytesByEmail(lookup);
        }
        if (refOpt.isEmpty() && hasKeycloakId) {
            refOpt = repo.findReferenceBytesByKeycloakUserId(keycloakUserId.strip());
        }
        return refOpt;
    }
}
