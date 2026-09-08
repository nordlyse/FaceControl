package com.primeapp.bridge.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnrollmentStatusRequest(String email, String username, String keycloakUserId) {}
