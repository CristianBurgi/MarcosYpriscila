-- Fase 9.0 - Bloque C: hace que POST /confirm sea seguro de reintentar. El frontend puede
-- llamarlo más de una vez con la MISMA upload_key (la key de la presigned URL, única por
-- intento de subida) si la respuesta de un intento anterior se perdió por la red -- el PUT
-- a R2 ya había terminado, no hace falta ni es seguro repetir todo desde cero.

-- Vínculo durable entre una foto ya creada y la upload_key que la originó: permite que una
-- llamada duplicada devuelva la foto existente en vez de crear otra o cobrar cupo de más.
-- Nullable: las fotos existentes (y las de upload-direct, que no tienen retry) quedan en
-- NULL; NULL no colisiona consigo mismo bajo un índice único (semántica SQL estándar).
ALTER TABLE photos ADD COLUMN IF NOT EXISTS upload_key VARCHAR(512);
CREATE UNIQUE INDEX IF NOT EXISTS uq_photos_upload_key ON photos (upload_key);

-- Mutex barato: se reclama ANTES de tocar storage/HEIC/cupo, para que una llamada
-- duplicada (reintento propio o una carrera concurrente real entre dos hilos) se detecte
-- de inmediato sin repetir trabajo costoso. failed_status/failed_message permiten
-- reconstruirle al perdedor de la carrera el MISMO error definitivo que vio el ganador
-- (archivo inválido, cupo agotado), en vez de un 503 genérico si el ganador ya terminó
-- en un rechazo.
CREATE TABLE IF NOT EXISTS photo_upload_claims (
    upload_key VARCHAR(512) PRIMARY KEY,
    claimed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failed_status INT,
    failed_message VARCHAR(500)
);
