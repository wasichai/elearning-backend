-- fines de las ediciones vigentes, alineados con inicios: «próximos» cuenta también la edición en curso
ALTER TABLE lms.catalogo_publicado ADD COLUMN fines date[] NOT NULL DEFAULT '{}';
-- obliga al siguiente refresco a reescribir todas las filas (el hash no cambió, pero falta la columna nueva)
UPDATE lms.catalogo_publicado SET hash = '';
