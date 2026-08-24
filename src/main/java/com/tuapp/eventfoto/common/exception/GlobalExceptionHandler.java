package com.tuapp.eventfoto.common.exception;

import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<ErrorResponseDTO> handleUnauthorizedAccess(
            UnauthorizedAccessException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponseDTO> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponseDTO> handleRateLimitExceeded(
            RateLimitExceededException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests - Rate Limit Exceeded",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
    }

    @ExceptionHandler(ContentModerationException.class)
    public ResponseEntity<ErrorResponseDTO> handleContentModeration(
            ContentModerationException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Unprocessable Entity - Content Moderated",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    @ExceptionHandler(EventClosedException.class)
    public ResponseEntity<ErrorResponseDTO> handleEventClosed(
            EventClosedException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request - Event Closed",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MaxUploadLimitReachedException.class)
    public ResponseEntity<ErrorResponseDTO> handleMaxUploadLimit(
            MaxUploadLimitReachedException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request - Max Photo Limit Reached",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(InvalidFileFormatException.class)
    public ResponseEntity<ErrorResponseDTO> handleInvalidFileFormat(
            InvalidFileFormatException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request - Invalid File Format",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(InvalidFileContentException.class)
    public ResponseEntity<ErrorResponseDTO> handleInvalidFileContent(
            InvalidFileContentException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Unprocessable Entity - Invalid File Content",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request - Validation Error",
                details,
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponseDTO> handleStorageException(
            StorageException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error - Storage Error",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleGenericException(
            Exception ex, HttpServletRequest request) {
        log.error("Excepción interna no capturada en {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        Sentry.captureException(ex);
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                ex.getMessage() != null ? ex.getMessage() : "Ocurrió un error inesperado.",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
