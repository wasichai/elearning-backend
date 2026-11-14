-- el orden de un curso en el banner de la portada pública (ADR-0046); null = no destacado. lo escribe CatalogProjection
-- desde curso_destacado y solo para cursos publicados, como el resto de la fila
ALTER TABLE lms.catalogo_publicado ADD COLUMN destacado_orden integer;

CREATE INDEX catalogo_publicado_destacado ON lms.catalogo_publicado (organizacion, destacado_orden) WHERE destacado_orden IS NOT NULL;
