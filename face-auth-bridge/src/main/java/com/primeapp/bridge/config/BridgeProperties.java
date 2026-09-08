package com.primeapp.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prime.face")
public class BridgeProperties {

    /**
     * Shared secret for Keycloak provider and other internal callers.
     */
    private String internalSecret = "change-me-face-bridge";

    /**
     * If true, users without a row in deepface.face_enrollment get HTTP 404 on verify.
     */
    private boolean requireEnrollment = true;

    public String getInternalSecret() {
        return internalSecret;
    }

    public void setInternalSecret(String internalSecret) {
        this.internalSecret = internalSecret;
    }

    public boolean isRequireEnrollment() {
        return requireEnrollment;
    }

    public void setRequireEnrollment(boolean requireEnrollment) {
        this.requireEnrollment = requireEnrollment;
    }
}
