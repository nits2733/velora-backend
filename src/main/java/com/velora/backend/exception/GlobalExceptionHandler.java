package com.velora.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, null);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicate(DuplicateResourceException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, null);
    }

    @ExceptionHandler(UnauthorizedActionException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthorizedAction(UnauthorizedActionException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), req, null);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidState(InvalidStateTransitionException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, null);
    }

    /**
     * Login only. The message is fixed and vague on purpose - saying which half was wrong
     * would let an attacker discover which emails have accounts.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", req, null);
    }

    /**
     * Every other 401. Unlike login, these say what actually failed: the caller already
     * holds whatever token is being rejected, so there's nothing to enumerate, and a
     * client needs to tell "refresh me" apart from "make the user log in again".
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationFailed(AuthenticationFailedException ex,
                                                                        HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", req, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "The request conflicts with existing data", req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiErrorResponse.FieldViolation> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiErrorResponse.FieldViolation(fe.getField(), messageOf(fe)))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, fieldErrors);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    /**
     * A path variable or query parameter that can't be converted to its declared type -
     * {@code /api/portfolio/abc}, {@code ?availability=MAYBE}. That's a malformed request,
     * not a server fault, so it must not fall through to the generic 500 handler below.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest req) {
        String message = "Invalid value for '" + ex.getName() + "'";
        List<ApiErrorResponse.FieldViolation> fieldErrors =
                List.of(new ApiErrorResponse.FieldViolation(ex.getName(), expectedTypeOf(ex)));
        return build(HttpStatus.BAD_REQUEST, message, req, fieldErrors);
    }

    /** A malformed or unreadable JSON body - again a client error, not a server one. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                  HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", req, null);
    }

    private static String expectedTypeOf(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        if (required != null && required.isEnum()) {
            return "Must be one of: " + String.join(", ",
                    java.util.Arrays.stream(required.getEnumConstants()).map(Object::toString).toList());
        }
        return required != null ? "Must be a valid " + required.getSimpleName() : "Invalid value";
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOtp(InvalidOtpException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    @ExceptionHandler(OtpExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleOtpExpired(OtpExpiredException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    @ExceptionHandler(MaxOtpAttemptsException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxOtpAttempts(MaxOtpAttemptsException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req, null);
    }

    @ExceptionHandler(ResendCooldownException.class)
    public ResponseEntity<ApiErrorResponse> handleResendCooldown(ResendCooldownException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req, null);
    }

    @ExceptionHandler(EmailSendException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailSend(EmailSendException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), req, null);
    }

    @ExceptionHandler(InvalidResetTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidResetToken(InvalidResetTokenException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", req, null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req, null);
    }

    @ExceptionHandler(MediaUploadException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaUpload(MediaUploadException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException ex,
                                                                HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Uploaded file exceeds the maximum allowed size limit (10MB)", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled server exception at URI: {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req, null);
    }

    private static String messageOf(FieldError fe) {
        return fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value";
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String message, HttpServletRequest req,
                                                    List<ApiErrorResponse.FieldViolation> fieldErrors) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                req.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }
}
