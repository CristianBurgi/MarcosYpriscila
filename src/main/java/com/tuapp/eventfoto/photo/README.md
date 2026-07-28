# Módulo Feature: Photo

Este paquete contiene los componentes para la gestión de fotografías:
- Recepción de imágenes subidas por los invitados (procesamiento multipart, validación de 10MB y formato).
- Entidad `Photo`, repositorios Spring Data JPA y DTOs de lectura/escritura.
- Endpoints REST para la galería de fotos públicas, destacados y administración.
- Disparo de eventos SSE al confirmar una nueva fotografía subida.
