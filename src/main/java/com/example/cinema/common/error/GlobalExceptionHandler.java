package com.example.cinema.common.error;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.example.cinema.common.error.ApiProblemFactory.FieldErrorDetail;
import com.example.cinema.common.infrastructure.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ApiProblemFactory problemFactory;

    public GlobalExceptionHandler(ApiProblemFactory problemFactory) {
        this.problemFactory = problemFactory;
    }

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemDetail> handleApplication(ApplicationException exception, HttpServletRequest request) {
        ProblemDetail problem = problemFactory.create(
                exception.status(), exception.errorCode(), exception.safeDetail(), request, List.of(),
                exception.retryable());
        HttpHeaders headers = new HttpHeaders();
        if (exception instanceof RateLimitExceededException rateLimit) {
            headers.set(HttpHeaders.RETRY_AFTER,
                    Long.toString(Math.max(1, (rateLimit.retryAfter().toMillis() + 999) / 1000)));
        }
        return new ResponseEntity<>(problem, headers, exception.status());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleBeanValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldErrorDetail> fields = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> new FieldErrorDetail(error.getField(), safeValidationMessage(error.getDefaultMessage())))
                .toList();
        return validationProblem(request, fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request) {
        List<FieldErrorDetail> fields = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldErrorDetail(
                                result.getMethodParameter().getParameterName(),
                                safeValidationMessage(error.getDefaultMessage()))))
                .sorted(Comparator.comparing(FieldErrorDetail::field))
                .toList();
        return validationProblem(request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintValidation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<FieldErrorDetail> fields = exception.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDetail(
                        leafProperty(violation.getPropertyPath().toString()),
                        safeValidationMessage(violation.getMessage())))
                .sorted(Comparator.comparing(FieldErrorDetail::field))
                .toList();
        return validationProblem(request, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body is invalid.", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_HEADER",
                "A required request header is missing.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PARAMETER",
                "A request parameter has an invalid value.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleMissingRoute(NoResourceFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The requested resource was not found.", request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(
            ObjectOptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource changed while the request was being processed.", request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handlePessimisticLock(
            PessimisticLockingFailureException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource changed while the request was being processed.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        logInternal(exception, request);
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        logInternal(exception, request);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The request could not be completed.", request);
    }

    private ResponseEntity<ProblemDetail> validationProblem(
            HttpServletRequest request,
            List<FieldErrorDetail> fields) {
        ProblemDetail problem = problemFactory.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "One or more request fields are invalid.", request, fields, false);
        return ResponseEntity.badRequest().body(problem);
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(problemFactory.create(status, code, detail, request));
    }

    private static String safeValidationMessage(String message) {
        return message == null || message.isBlank() ? "is invalid" : message;
    }

    private static String leafProperty(String propertyPath) {
        int separator = propertyPath.lastIndexOf('.');
        return separator >= 0 ? propertyPath.substring(separator + 1) : propertyPath;
    }

    private static void logInternal(Exception exception, HttpServletRequest request) {
        LOGGER.error("Request failed; correlationId={}, exceptionType={}, stackTrace={}",
                CorrelationIdFilter.from(request), exception.getClass().getName(),
                Arrays.toString(exception.getStackTrace()));
    }
}
