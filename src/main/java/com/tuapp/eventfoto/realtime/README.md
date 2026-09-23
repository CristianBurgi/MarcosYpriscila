# Módulo Feature: Realtime

Este paquete maneja la transmisión Server-Sent Events (SSE):
- `SseBroadcaster` gestiona los suscriptores activos por evento y el heartbeat cada 25 s.
- Eventos emitidos: `PHOTO_PUBLISHED`, `PHOTO_DELETED`, `MESSAGE_CREATED`, `MESSAGE_DELETED`, `COMMENT_DELETED`.
- Un cliente que se desconecta (celular sin señal, pestaña cerrada) se remueve sin afectar al resto y se loguea en `debug`, no como error.
