# Módulo Feature: Storage

Este paquete contiene la capa de abstracción del almacenamiento de objetos:
- Cliente AWS S3 v2 configurado para interactuar con Cloudflare R2 (sin costo de egress).
- `StorageService` interface y su implementación `CloudflareR2StorageService`.
- Métodos para subida de imágenes, generación de URLs públicas y eliminación de objetos.
