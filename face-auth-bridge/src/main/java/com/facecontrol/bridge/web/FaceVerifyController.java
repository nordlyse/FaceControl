package com.facecontrol.bridge.web;

import com.facecontrol.bridge.dto.EnrollResponse;
import com.facecontrol.bridge.dto.EnrollmentStatusRequest;
import com.facecontrol.bridge.dto.EnrollmentStatusResponse;
import com.facecontrol.bridge.dto.VerifyRequest;
import com.facecontrol.bridge.dto.VerifyResponse;
import com.facecontrol.bridge.service.FaceVerifyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/api/v1")
public class FaceVerifyController {

    private final FaceVerifyService faceVerifyService;

    public FaceVerifyController(FaceVerifyService faceVerifyService) {
        this.faceVerifyService = faceVerifyService;
    }

    @PostMapping(value = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VerifyResponse> verify(@RequestBody VerifyRequest request) {
        VerifyResponse resp =
                faceVerifyService.verify(request.email(), request.username(), request.keycloakUserId(), request.faceImageBase64());
        if (!resp.verified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping(
            value = "/enrollment-status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public EnrollmentStatusResponse enrollmentStatus(@RequestBody EnrollmentStatusRequest request) {
        boolean enrolled =
                faceVerifyService.hasEnrollment(request.email(), request.username(), request.keycloakUserId());
        return new EnrollmentStatusResponse(enrolled);
    }

    @PostMapping(value = "/enroll", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EnrollResponse enroll(@RequestBody VerifyRequest request) {
        faceVerifyService.enrollReference(
                request.email(), request.username(), request.keycloakUserId(), request.faceImageBase64());
        return new EnrollResponse(true);
    }
}
