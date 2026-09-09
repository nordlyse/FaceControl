package com.facecontrol.bridge.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VerifyRequest(
        String email,
        String username,
        String faceImageBase64,
        String keycloakUserId
) {}
