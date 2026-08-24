-- Migration V5: Tabla guest_quotas para limitar la cantidad de fotos por invitado por evento
CREATE TABLE IF NOT EXISTS guest_quotas (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    guest_token VARCHAR(64) NOT NULL,
    photos_uploaded INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_guest_quotas_event FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT uq_guest_quotas_event_token UNIQUE (event_id, guest_token)
);

-- Índice para resolver rápido el cupo de un invitado dentro de un evento
CREATE INDEX idx_guest_quotas_event_id ON guest_quotas(event_id);
