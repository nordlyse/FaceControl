package com.facecontrol.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "face")
public class BridgeProperties {

    private String internalSecret = "change-me-face-bridge";
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
