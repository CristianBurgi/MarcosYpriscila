-- Fase 9.7-B: demo en vivo. No es un evento: tablas propias, sin organizador, sin ventana de subida ni purga de la
-- 9.6. Cada demo dura 30 minutos (DemoCleanupJob la borra: R2 bajo demo/{sid}/ y estas filas).
--
-- sid: 32 bytes aleatorios en base64url (43 caracteres). Va en la URL y es la única llave de la demo.
-- slot: posición 1..5 de la foto o el mensaje dentro de la demo. unique(sid, slot) hace atómico el tope de 5, también
-- con dos requests simultáneas: la que llega segunda al mismo slot choca contra la restricción.
CREATE TABLE demo_session (
    sid VARCHAR(43) PRIMARY KEY,
    color VARCHAR(7) NOT NULL,
    background_key VARCHAR(255) NULL,
    event_date DATE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_demo_session_created_at ON demo_session(created_at);

CREATE TABLE demo_photo (
    id UUID PRIMARY KEY,
    sid VARCHAR(43) NOT NULL REFERENCES demo_session(sid),
    slot SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 5),
    object_key VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_demo_photo_slot UNIQUE (sid, slot)
);

CREATE TABLE demo_message (
    id UUID PRIMARY KEY,
    sid VARCHAR(43) NOT NULL REFERENCES demo_session(sid),
    slot SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 5),
    author_name VARCHAR(150) NOT NULL,
    text VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_demo_message_slot UNIQUE (sid, slot)
);
