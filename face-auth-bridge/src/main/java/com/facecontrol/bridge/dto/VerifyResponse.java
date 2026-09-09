package com.facecontrol.bridge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifyResponse(boolean verified, Double distance, Double threshold, String model, String message) {}
