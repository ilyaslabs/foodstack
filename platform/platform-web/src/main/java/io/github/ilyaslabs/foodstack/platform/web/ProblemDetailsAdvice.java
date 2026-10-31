package io.github.ilyaslabs.foodstack.platform.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 {@code application/problem+json} for every error of the public API (TDD 8.1): a stable
 * {@code code}, the {@code traceId}, and {@code errors[]} for validation failures. A module error
 * looks the same whether the module that threw it is local or remote.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ProblemDetailsAdvice {

    private static final Log logger = LogFactory.getLog(ProblemDetailsAdvice.class);

    @ExceptionHandler
    ResponseEntity<ProblemDetail> moduleError(ModuleException ex) {
        HttpStatusCode status = HttpStatusCode.valueOf(ex.status());
        if (status.is5xxServerError()) {
            logger.error("Module error", ex);
        }
        String detail = status.is5xxServerError() ? "The request could not be completed." : ex.getMessage();
        ProblemDetail problem = problem(status, detail, ex.code());
        if (!ex.details().isEmpty()) {
            problem.setProperty("details", ex.details());
        }
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "The request is invalid.", "VALIDATION_FAILED");
        List<Map<String, String>> errors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(ProblemDetailsAdvice::fieldError)
            .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** A problem with the FoodStack extension members. */
    public static ProblemDetail problem(HttpStatusCode status, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (code != null) {
            problem.setProperty("code", code);
        }
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    private static Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(), "message",
                (error.getDefaultMessage() != null) ? error.getDefaultMessage() : "is invalid");
    }
}
