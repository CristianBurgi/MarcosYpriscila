# Módulo Common: Config

Este paquete alberga todas las clases de configuración del proyecto Spring Boot:
- `SecurityConfig`: Configuración de Spring Security, filtros JWT, CORS y permisos.
- `S3Config`: Bean de configuración para AWS S3 SDK (Cloudflare R2).
- `WebConfig`: Ajustes de MVC, recursos estáticos y CORS.

## Pendiente conocido: rate limits por IP y salones con WiFi compartido
`RateLimiterService` limita por IP y por guestToken, y los contadores NO son por evento. En un salón
donde todos los invitados salen por la misma IP pública (el hallazgo de la Fase 8), los límites por IP
pueden frenar a un salón entero; y dos eventos que compartan IP se limitan entre sí. Anotado en el
Bloque 4 de la fase 9.1; no se cambió ahí.
