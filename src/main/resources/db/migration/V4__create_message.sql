-- Migration V4: Tabla messages con UUID, moderación (is_approved) e índice en event_id
CREATE TABLE IF NOT EXISTS messages (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    author_name VARCHAR(150) NOT NULL,
    text TEXT NOT NULL,
    is_approved BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_messages_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE
);

-- Índice para recuperar los mensajes del libro de visitas por evento
CREATE INDEX idx_messages_event_id ON messages(event_id);
-- Índice para filtrado de mensajes aprobados por moderación
CREATE INDEX idx_messages_approved ON messages(is_approved);
