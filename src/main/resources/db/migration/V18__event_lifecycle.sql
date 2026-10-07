-- Fase 9.6: ciclo de vida automático del evento (ver UploadWindow y EventLifecycleService).
--
-- upload_deadline (V1) era una ventana provisoria de 90 días desde la creación que nada aplicaba: la ventana real
-- se calcula en cada request a partir de event_date.
ALTER TABLE events DROP COLUMN upload_deadline;

-- Fecha de borrado fijada por el superadmin (9.7). NULL = event_date + 30 días.
ALTER TABLE events ADD COLUMN retention_override_until DATE NULL;
-- Reserva del mail "tu álbum se borra pronto": sale una sola vez por evento.
ALTER TABLE events ADD COLUMN expiry_reminder_sent_at TIMESTAMP WITH TIME ZONE NULL;
-- El contenido (R2 + fotos, mensajes, comentarios, cupos) ya se borró. La fila se conserva: pagos y "Mis eventos".
ALTER TABLE events ADD COLUMN purged_at TIMESTAMP WITH TIME ZONE NULL;
