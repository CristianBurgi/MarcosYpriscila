-- Migration V6: Fase 9.0 - publicación automática de fotos.
-- La aprobación manual se elimina: toda foto confirmada queda publicada al instante
-- y el borrado (DELETE /api/v1/admin/photos/{id}) pasa a ser el único control de moderación.

-- Resguardo: publicar cualquier foto que haya quedado pendiente antes de borrar la columna.
-- (Al 23/09/2026 producción tenía 0 pendientes y 51 publicadas.)
UPDATE photos SET is_approved = TRUE WHERE is_approved = FALSE;

DROP INDEX IF EXISTS idx_photos_approved;

ALTER TABLE photos DROP COLUMN is_approved;
