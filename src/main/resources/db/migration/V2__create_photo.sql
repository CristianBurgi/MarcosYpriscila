-- Migration V2: Tabla photos con UUID e índice en event_id
CREATE TABLE IF NOT EXISTS photos (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    uploader_name VARCHAR(150),
    is_approved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_photos_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE
);

-- Índice para consultas de fotos por evento
CREATE INDEX idx_photos_event_id ON photos(event_id);
-- Índice para filtrado de fotos aprobadas en la galería pública
CREATE INDEX idx_photos_approved ON photos(is_approved);
