-- Migration V8: cierre de la boda de Marcos y Priscila (19/09/2026) - Fase 9.1.
-- Cristian ya respaldó todo lo necesario (álbum, libro de visitas, material de
-- testimonios y horarios de subida). Se borran todos los datos del evento en BD
-- para que la 9.1 arranque sobre tablas vacías, sin organizador semilla ni fechas
-- precargadas.
--
-- Orden: hijos antes que padres, aunque las FKs ya tienen ON DELETE CASCADE desde
-- 'events' -- los DELETE explícitos no dependen de que esa cascada exista o cambie
-- en el futuro. 'photo_upload_claims' NO tiene FK a 'events' (ver V7): se borra
-- aparte, o quedaría huérfana sin que ningún cascade la alcance.
--
-- Este archivo es solo BASE DE DATOS. El vaciado del bucket R2 es un paso manual
-- aparte, después de que esta migración corra en producción (ver PR).
DELETE FROM comments;
DELETE FROM messages;
DELETE FROM guest_quotas;
DELETE FROM photos;
DELETE FROM photo_upload_claims;
DELETE FROM events;
