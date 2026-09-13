package com.llmgateway.admin;

import com.llmgateway.discovery.ProviderAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice(basePackages = "com.llmgateway.admin")
public class AdminExceptionHandler {
    @ExceptionHandler(com.llmgateway.inference.GatewayException.class)
    public ResponseEntity<ErrorResponse> gateway(com.llmgateway.inference.GatewayException exception) {
        return ResponseEntity.status(exception.error().status()).body(new ErrorResponse(exception.error().name(), exception.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse badRequest(IllegalArgumentException exception) { return new ErrorResponse("INVALID_REQUEST", exception.getMessage()); }

    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse validation(WebExchangeBindException exception) {
        String fields = exception.getFieldErrors().stream().map(error -> error.getField()).distinct().sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        return new ErrorResponse("INVALID_REQUEST", "Invalid fields: " + fields);
    }

    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidBody() {
        return new ErrorResponse("INVALID_REQUEST", "Request body or parameters are invalid");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ErrorResponse("NOT_FOUND", exception.getReason()));
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse conflict() {
        return new ErrorResponse("CONFIGURATION_CONFLICT",
                "Configuration conflicts with an existing record or reference; refresh and check related records");
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse storageUnavailable() {
        return new ErrorResponse("STORAGE_UNAVAILABLE", "Configuration storage is unavailable");
    }

    @ExceptionHandler(ProviderAccessException.class)
    public ResponseEntity<ErrorResponse> providerAccess(ProviderAccessException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
