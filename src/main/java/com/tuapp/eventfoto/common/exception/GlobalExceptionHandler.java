package com.tuapp.eventfoto.common.exception;

import io.sentry.Sentry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.ClientAbortException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
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

    @ExceptionHandler(InvalidActivationTokenException.class)
    public ResponseEntity<ErrorResponseDTO> handleInvalidActivationToken(
            InvalidActivationTokenException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request - Invalid Activation Token",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(GuestQuotaExceededException.class)
    public ResponseEntity<ErrorResponseDTO> handleGuestQuotaExceeded(
            GuestQuotaExceededException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.FORBIDDEN.value(),
                "Forbidden - Guest Photo Quota Exceeded",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
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

    // --- Fase 9.0 (Bloque C): idempotencia de /confirm ante reintentos/carreras ---

    @ExceptionHandler(UploadInProgressException.class)
    public ResponseEntity<ErrorResponseDTO> handleUploadInProgress(
            UploadInProgressException ex, HttpServletRequest request) {
        log.debug("Confirmación duplicada en curso para {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable - Upload In Progress",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    @ExceptionHandler(UploadClaimFailedException.class)
    public ResponseEntity<ErrorResponseDTO> handleUploadClaimFailed(
            UploadClaimFailedException ex, HttpServletRequest request) {
        ErrorResponseDTO error = ErrorResponseDTO.of(
                ex.getStatus(),
                "Upload Previously Failed",
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    // --- Fase 9.0: errores de subida con mensaje para el invitado (no un 500 técnico) ---

    public static final String UPLOAD_TOO_LARGE_MESSAGE = "La foto es demasiado pesada, probá con otra";
    public static final String UPLOAD_FAILED_MESSAGE = "Hubo un problema con la subida, intentá de nuevo";

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponseDTO> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.info("Subida rechazada por tamaño en {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Payload Too Large - Upload Size Exceeded",
                UPLOAD_TOO_LARGE_MESSAGE,
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
    }

    /**
     * Multipart malformado o cortado a mitad de camino (típico con mala señal en el salón).
     * Es un problema del lado del cliente/red, no un bug del servidor: 400 sin Sentry.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponseDTO> handleMultipartException(
            MultipartException ex, HttpServletRequest request) {
        log.info("Subida multipart inválida o interrumpida en {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request - Malformed Upload",
                UPLOAD_FAILED_MESSAGE,
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Recursos estáticos inexistentes (favicon de navegadores viejos, bots escaneando rutas):
     * un 404 normal, no un error a reportar.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponseDTO> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("Recurso estático inexistente: {}", request.getRequestURI());
        ErrorResponseDTO error = ErrorResponseDTO.of(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                "El recurso solicitado no existe.",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Cliente desconectado (SSE de un celular que perdió señal o cerró la pestaña).
     *
     * Fase 9.0: cuando SseBroadcaster falla al escribir, Spring hace un async error dispatch
     * con esa misma IOException, que caía en handleGenericException -> Sentry ("Broken
     * pipe", 217 eventos en la boda). No hay a quién responder: se loguea en debug y se
     * devuelve sin body.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnected(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("Cliente desconectado en {}: {}", request.getRequestURI(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleGenericException(
            Exception ex, HttpServletRequest request) {
        if (isClientDisconnect(ex, request)) {
            // Mismo caso que handleClientDisconnected, cuando la excepción llega sin envolver.
            // Se devuelve null (sin body): intentar escribir un JSON sobre la conexión muerta
            // solo produce otra IOException que Tomcat loguea como ERROR.
            log.debug("Cliente desconectado en {}: {}", request.getRequestURI(), ex.getMessage());
            return null;
        }
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            // Excepciones 4xx propias de Spring MVC (método no soportado, parámetro faltante,
            // content-type inválido): son errores del cliente, no bugs. Se respeta su status
            // en lugar de convertirlas en un 500 que además llegaba a Sentry.
            HttpStatusCode status = errorResponse.getStatusCode();
            log.info("Request inválida en {}: {}", request.getRequestURI(), ex.getMessage());
            ErrorResponseDTO error = ErrorResponseDTO.of(
                    status.value(),
                    "Client Error",
                    errorResponse.getBody().getDetail() != null ? errorResponse.getBody().getDetail() : "Solicitud inválida.",
                    request.getRequestURI()
            );
            return ResponseEntity.status(status).body(error);
        }
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

    /**
     * Solo cuenta como "cliente desconectado" una falla ESCRIBIENDO LA RESPUESTA al
     * cliente. Una falla leyendo de R2 o de la base es un error real y tiene que llegar
     * a Sentry, aunque su mensaje diga "Connection reset" o sea un EOFException.
     *
     * - ClientAbortException: Tomcat la lanza únicamente al escribir en la respuesta de
     *   un cliente que cortó (ej. cancela la descarga del ZIP).
     * - IOException durante un dispatch ASYNC: el único uso asíncrono de la app es el SSE
     *   (SseEmitter); ese dispatch lo dispara Spring cuando falla la escritura del stream.
     *   Verificado reproduciendo el corte en local (Fase 9.0): llega la IOException CRUDA,
     *   con un mensaje que depende del sistema operativo (en Windows sale localizado).
     *   Si algún día se agrega un endpoint asíncrono que lea de R2 (Callable/DeferredResult),
     *   este criterio hay que revisarlo.
     *
     * NO se usa DisconnectedClientHelper.isClientDisconnectedException(): decide por el
     * texto del mensaje ("connection reset", "broken pipe") o por tipo (EOFException) de
     * CUALQUIER excepción, sin distinguir si la falla fue del lado del cliente o de R2/BD.
     */
    private static boolean isClientDisconnect(Exception ex, HttpServletRequest request) {
        return ex instanceof ClientAbortException
                || (ex instanceof IOException && request.getDispatcherType() == DispatcherType.ASYNC);
    }
}
