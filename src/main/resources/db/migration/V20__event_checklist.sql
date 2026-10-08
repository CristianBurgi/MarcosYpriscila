-- Fase 9.8: checklist previa del organizador. Máscara de bits de ChecklistItem (5 casillas fijas); 0 = nada marcado.
ALTER TABLE events ADD COLUMN checklist SMALLINT NOT NULL DEFAULT 0;
