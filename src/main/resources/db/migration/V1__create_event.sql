-- Migration V1: Tabla events con UUID e índice único en slug
CREATE TABLE IF NOT EXISTS events (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    event_date TIMESTAMP WITH TIME ZONE NOT NULL,
    upload_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Índice único para acelerar la búsqueda por slug en URLs públicas (ej: /marcos-y-priscila)
CREATE UNIQUE INDEX idx_events_slug ON events(slug);
