-- proyección pública del catálogo (ADR-0011). la escribe CatalogProjection en segundo plano y solo la lee la ruta
-- anónima: aquí no entra nada que no esté publicado. se puede reconstruir entera en cualquier momento
CREATE SCHEMA IF NOT EXISTS lms;

CREATE TABLE lms.catalogo_publicado (
    organizacion      text        NOT NULL,
    slug              text        NOT NULL,
    codigo            text        NOT NULL,
    titulo            text        NOT NULL,
    resumen           text,
    categoria_slug    text,
    categoria_nombre  text,
    nivel             text        NOT NULL,
    modalidad         text        NOT NULL,
    semanas           integer     NOT NULL,
    -- inicios de las ediciones vigentes: «desde» y «próximos» sin abrir la ficha
    inicios           date[]      NOT NULL DEFAULT '{}',
    ficha             jsonb       NOT NULL,
    hash              text        NOT NULL,
    actualizado_en    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organizacion, slug)
);

CREATE INDEX catalogo_publicado_categoria ON lms.catalogo_publicado (organizacion, categoria_slug);
CREATE INDEX catalogo_publicado_nivel_modalidad ON lms.catalogo_publicado (organizacion, nivel, modalidad);
