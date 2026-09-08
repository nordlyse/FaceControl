package com.primeapp.keycloak.face;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.messages.Messages;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public class PrimeFaceAuthenticator extends AbstractUsernameFormAuthenticator {

    private static final Logger LOG = Logger.getLogger(PrimeFaceAuthenticator.class);

    private static final Pattern BRIDGE_JSON_VERIFIED_TRUE =
            Pattern.compile("\"verified\"\\s*:\\s*true\\b");
    private static final Pattern BRIDGE_JSON_VERIFIED_FALSE =
            Pattern.compile("\"verified\"\\s*:\\s*false\\b");
    private static final Pattern BRIDGE_JSON_ENROLLED_TRUE =
            Pattern.compile("\"enrolled\"\\s*:\\s*true\\b");
    private static final Pattern BRIDGE_JSON_ENROLLED_FALSE =
            Pattern.compile("\"enrolled\"\\s*:\\s*false\\b");

    public static final String FORM_FIELD_FACE = "prime_face_image";

    private static final String AUTH_NOTE_FACE_MODE = "prime_face_mode";

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private static boolean bridgeDisabled() {
        return "true".equalsIgnoreCase(Objects.requireNonNullElse(System.getenv("PRIME_FACE_DISABLED"), ""));
    }

    private static boolean allowMissingEnrollment() {
        return "true".equalsIgnoreCase(System.getenv().getOrDefault("PRIME_FACE_OPTIONAL_NO_ENROLL", ""));
    }

    /** First login: save captured photo as DB reference; later logins verify against it. Default false for backward compatibility. */
    private static boolean selfEnrollOnFirstLogin() {
        return "true".equalsIgnoreCase(
                System.getenv().getOrDefault("PRIME_FACE_SELF_ENROLL_ON_FIRST_LOGIN", "false"));
    }

    private static String bridgeUrl() {
        String url = Objects.requireNonNullElse(System.getenv("PRIME_FACE_BRIDGE_URL"), "").strip();
        if (url.isEmpty()) {
            return "http://face-auth-bridge:8071";
        }
        return url.replaceAll("/$", "");
    }

    private static String bridgeSecret() {
        return Objects.requireNonNullElse(System.getenv("PRIME_FACE_BRIDGE_SECRET"), "").strip();
    }

    private static Duration bridgeTimeout() {
        try {
            return Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("PRIME_FACE_BRIDGE_TIMEOUT_MS", "45000")));
        } catch (NumberFormatException e) {
            return Duration.ofMillis(45000);
        }
    }

    static String escapeJsonNullable(String text) {
        if (text == null || text.isBlank()) {
            return "null";
        }
        String e = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + e + "\"";
    }

    /**
     * OIDC silent checks ({@code prompt=none}) must not render interactive HTML; skipping this step avoids protocol errors
     * and matches behaviour expected for iframe/token renewal requests. Full interactive login still runs face verification.
     */
    private static boolean clientRequestedPromptNone(AuthenticationFlowContext context) {
        String prompt =
                context.getAuthenticationSession().getClientNote(OIDCLoginProtocol.PROMPT_PARAM);
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        for (String p : prompt.trim().split("\\s+")) {
            if (OIDCLoginProtocol.PROMPT_VALUE_NONE.equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (bridgeDisabled()) {
            context.success();
            return;
        }
        if (clientRequestedPromptNone(context)) {
            LOG.debug("Skipping Prime DeepFace for OIDC prompt=none (silent auth)");
            context.success();
            return;
        }
        UserModel user = context.getUser();
        if (user == null) {
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
            return;
        }
        LoginFormsProvider form = context.form().setExecution(context.getExecution().getId());
        String mode = resolveFaceMode(user);
        context.getAuthenticationSession().setAuthNote(AUTH_NOTE_FACE_MODE, mode);
        form.setAttribute("prime_face_first_enroll", "enroll".equals(mode));
        context.challenge(form.createForm("prime-face-verify.ftl"));
    }

    private String resolveFaceMode(UserModel user) {
        if (!selfEnrollOnFirstLogin() || bridgeSecret().isEmpty()) {
            return "verify";
        }
        Boolean enrolled = fetchEnrollmentStatus(user);
        if (Boolean.FALSE.equals(enrolled)) {
            return "enroll";
        }
        return "verify";
    }

    private Boolean fetchEnrollmentStatus(UserModel user) {
        try {
            String json =
                    "{"
                            + "\"email\":"
                            + escapeJsonNullable(user.getEmail())
                            + ",\"username\":"
                            + escapeJsonNullable(user.getUsername())
                            + ",\"keycloakUserId\":"
                            + escapeJsonNullable(user.getId())
                            + "}";
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(bridgeUrl() + "/internal/api/v1/enrollment-status"))
                            .timeout(bridgeTimeout())
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .header("X-Internal-Face-Secret", bridgeSecret())
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code != 200) {
                LOG.warnf("face-auth-bridge enrollment-status HTTP %d", code);
                return null;
            }
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            if (BRIDGE_JSON_ENROLLED_TRUE.matcher(body).find()) {
                return true;
            }
            if (BRIDGE_JSON_ENROLLED_FALSE.matcher(body).find()) {
                return false;
            }
            return null;
        } catch (Exception e) {
            LOG.warn("face-auth-bridge enrollment-status failed", e);
            return null;
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        if (bridgeDisabled()) {
            context.success();
            return;
        }
        validateFace(context);
    }

    private void validateFace(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        if (!enabledUser(context, user)) {
            return;
        }
        MultivaluedMap<String, String> formParams = context.getHttpRequest().getDecodedFormParameters();
        String b64 = formParams.getFirst(FORM_FIELD_FACE);
        if (b64 == null || b64.isBlank()) {
            context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
            Response challengeResponse = challenge(context, Messages.INVALID_CODE, FORM_FIELD_FACE);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challengeResponse);
            return;
        }
        if (bridgeSecret().isEmpty()) {
            LOG.error("PRIME_FACE_BRIDGE_SECRET is unset; rejecting face MFA");
            context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
            LoginFormsProvider f = faceForm(context).setError("Face bridge secret is not configured on Keycloak.");
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, f.createForm("prime-face-verify.ftl"));
            return;
        }

        String mode = context.getAuthenticationSession().getAuthNote(AUTH_NOTE_FACE_MODE);
        if ("enroll".equals(mode)) {
            validateEnroll(context, user, b64.strip());
            return;
        }

        BridgeResult outcome =
                callBridgeVerify(user.getEmail(), user.getUsername(), user.getId(), b64.strip());

        switch (outcome) {
            case OK -> context.success();
            case NO_ENROLL_ALLOWED -> context.success();
            case NO_ENROLL_DENIED -> {
                context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
                LoginFormsProvider f = faceForm(context).setError("Face enrollment is required for this account.");
                context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, f.createForm("prime-face-verify.ftl"));
            }
            case FORBIDDEN -> {
                context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
                Response challengeResponse =
                        challenge(context, Messages.INVALID_CODE, FORM_FIELD_FACE);
                context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challengeResponse);
            }
            case ERROR -> {
                context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
                LoginFormsProvider f =
                        faceForm(context).setError("Face verification service is unavailable. Try again later.");
                context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, f.createForm("prime-face-verify.ftl"));
            }
        }
    }

    private LoginFormsProvider faceForm(AuthenticationFlowContext context) {
        String mode = context.getAuthenticationSession().getAuthNote(AUTH_NOTE_FACE_MODE);
        return context.form()
                .setExecution(context.getExecution().getId())
                .setAttribute("prime_face_first_enroll", "enroll".equals(mode));
    }

    private void validateEnroll(AuthenticationFlowContext context, UserModel user, String faceImageBase64) {
        EnrollOutcome outcome =
                callBridgeEnroll(user.getEmail(), user.getUsername(), user.getId(), faceImageBase64);
        switch (outcome) {
            case OK -> context.success();
            case NO_PRIME_USER -> {
                context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
                LoginFormsProvider f =
                        faceForm(context)
                                .setError(
                                        "No PrimeApp user matches this account email. Create your application user first.");
                context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, f.createForm("prime-face-verify.ftl"));
            }
            case ERROR -> {
                context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
                LoginFormsProvider f =
                        faceForm(context)
                                .setError("Could not save face enrollment. Check face-auth-bridge logs and try again.");
                context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, f.createForm("prime-face-verify.ftl"));
            }
        }
    }

    private enum BridgeResult {
        OK,
        NO_ENROLL_ALLOWED,
        NO_ENROLL_DENIED,
        FORBIDDEN,
        ERROR
    }

    private enum EnrollOutcome {
        OK,
        NO_PRIME_USER,
        ERROR
    }

    private BridgeResult callBridgeVerify(String email, String username, String keycloakUserId, String faceImageBase64) {
        String base = bridgeUrl();
        String json =
                "{"
                        + "\"email\":"
                        + escapeJsonNullable(email)
                        + ",\"username\":"
                        + escapeJsonNullable(username)
                        + ",\"keycloakUserId\":"
                        + escapeJsonNullable(keycloakUserId)
                        + ",\"faceImageBase64\":"
                        + escapeJsonNullable(faceImageBase64)
                        + "}";
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(base + "/internal/api/v1/verify"))
                            .timeout(bridgeTimeout())
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .header("X-Internal-Face-Secret", bridgeSecret())
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code == 404) {
                return allowMissingEnrollment() ? BridgeResult.NO_ENROLL_ALLOWED : BridgeResult.NO_ENROLL_DENIED;
            }
            if (code == 403) {
                return BridgeResult.FORBIDDEN;
            }
            if (code < 200 || code >= 300) {
                LOG.warnf("face-auth-bridge HTTP %d", code);
                return BridgeResult.ERROR;
            }
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            if (BRIDGE_JSON_VERIFIED_TRUE.matcher(body).find()) {
                return BridgeResult.OK;
            }
            if (BRIDGE_JSON_VERIFIED_FALSE.matcher(body).find()) {
                return BridgeResult.FORBIDDEN;
            }
            return BridgeResult.ERROR;
        } catch (Exception e) {
            LOG.warn("face-auth-bridge call failed", e);
            return BridgeResult.ERROR;
        }
    }

    private EnrollOutcome callBridgeEnroll(String email, String username, String keycloakUserId, String faceImageBase64) {
        String json =
                "{"
                        + "\"email\":"
                        + escapeJsonNullable(email)
                        + ",\"username\":"
                        + escapeJsonNullable(username)
                        + ",\"keycloakUserId\":"
                        + escapeJsonNullable(keycloakUserId)
                        + ",\"faceImageBase64\":"
                        + escapeJsonNullable(faceImageBase64)
                        + "}";
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(bridgeUrl() + "/internal/api/v1/enroll"))
                            .timeout(bridgeTimeout())
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .header("X-Internal-Face-Secret", bridgeSecret())
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code == 404) {
                return EnrollOutcome.NO_PRIME_USER;
            }
            if (code >= 200 && code < 300) {
                return EnrollOutcome.OK;
            }
            LOG.warnf("face-auth-bridge enroll HTTP %d", code);
            return EnrollOutcome.ERROR;
        } catch (Exception e) {
            LOG.warn("face-auth-bridge enroll failed", e);
            return EnrollOutcome.ERROR;
        }
    }

    @Override
    protected Response createLoginForm(LoginFormsProvider form) {
        return form.createForm("prime-face-verify.ftl");
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // no-op
    }
}
