-- Migration V3: Tabla comments con UUID, moderación (is_approved) e índice en photo_id
-- NOTA DE DISEÑO: Se configura 'ON DELETE CASCADE' para que al eliminar una fotografía se borren
-- automáticamente todos sus comentarios asociados en la base de datos PostgreSQL.
CREATE TABLE IF NOT EXISTS comments (
    id UUID PRIMARY KEY,
    photo_id UUID NOT NULL,
    author_name VARCHAR(150) NOT NULL,
    text TEXT NOT NULL,
    is_approved BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comments_photo FOREIGN KEY (photo_id) REFERENCES photos(id) ON DELETE CASCADE
);

-- Índice para consultas rápidas de comentarios por fotografía
CREATE INDEX idx_comments_photo_id ON comments(photo_id);
-- Índice para filtrado de comentarios aprobados por moderación
CREATE INDEX idx_comments_approved ON comments(is_approved);
