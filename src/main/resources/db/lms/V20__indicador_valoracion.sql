-- los indicadores de valoraciones (ADR-0038 §9–§11, RF-REP-05): media simple y n por dimensión, sin nadie dentro: ni
-- estudiante, ni matrícula, ni fecha de una valoración. los de una edición (destino DOCENCIA, sujeto la asignación) se
-- congelan al liberarse y no se recalculan; los de un recurso (destino RECURSO, sujeto la versión, edicion_id nulo) se
-- recalculan cada día y al suprimir a una persona. criterio: la versión de la fórmula con que se calculó
CREATE TABLE lms.indicador_valoracion (
    organizacion_id uuid          NOT NULL,
    destino         text          NOT NULL CHECK (destino IN ('DOCENCIA', 'RECURSO')),
    sujeto_id       uuid          NOT NULL,
    edicion_id      uuid,
    dimension       text          NOT NULL,
    n               integer       NOT NULL CHECK (n >= 0),
    media           numeric(4, 2),
    criterio        text          NOT NULL,
    congelado       boolean       NOT NULL DEFAULT false,
    calculado_en    timestamptz   NOT NULL,
    CHECK ((destino = 'DOCENCIA') = (edicion_id IS NOT NULL))
);

CREATE UNIQUE INDEX indicador_valoracion_clave
    ON lms.indicador_valoracion (organizacion_id, destino, sujeto_id, COALESCE(edicion_id, '00000000-0000-0000-0000-000000000000'::uuid), dimension);

-- una edición liberada: sus indicadores ya están congelados y no se vuelven a calcular
CREATE TABLE lms.liberacion_valoracion (
    organizacion_id uuid        NOT NULL,
    edicion_id      uuid        NOT NULL,
    liberada_en     timestamptz NOT NULL,
    PRIMARY KEY (organizacion_id, edicion_id)
);
