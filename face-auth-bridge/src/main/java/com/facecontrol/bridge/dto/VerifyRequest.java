package com.facecontrol.bridge.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VerifyRequest(
        String email,
        String username,
        String faceImageBase64,
        /** Keycloak user UUID; matches deepface.face_enrollment.keycloak_user_id when email lookup fails */
        String keycloakUserId
) {}
