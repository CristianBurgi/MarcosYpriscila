# Módulo Common: Exception

Este paquete contiene la infraestructura de manejo de errores y excepciones de negocio:
- Excepciones personalizadas (`BusinessException`, `ResourceNotFoundException`, `StorageException`, `MaxUploadLimitReachedException`).
- `GlobalExceptionHandler` con la anotación `@ControllerAdvice` para retornar respuestas estandarizadas `ErrorResponseDTO`.
