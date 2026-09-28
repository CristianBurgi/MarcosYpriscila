-- Migration V9: V8 borró todos los datos del evento, pero un CommandLineRunner
-- (DataInitializer, eliminado en este mismo commit) recreaba el evento semilla
-- 'marcos-y-priscila' en cada arranque de la app si no lo encontraba -- así que
-- en el primer deploy de V8 quedó una fila nueva y vacía en 'events' (0 fotos,
-- 0 mensajes, 0 cupos: nada que cascadee). Sin el CommandLineRunner ya eliminado,
-- esta vez el borrado es definitivo.
DELETE FROM events;
