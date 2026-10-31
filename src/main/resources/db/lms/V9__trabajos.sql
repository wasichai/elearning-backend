-- cola persistente de trabajos (ADR-0018 §4): lo largo o bloqueante (importar, generar) corre fuera del hilo de WebFlux.
-- cada réplica reclama con FOR UPDATE SKIP LOCKED solo los tipos que sabe ejecutar; un trabajo EN_CURSO cuyo reclamo
-- caducó (la réplica murió) se vuelve a reclamar. intentos cuenta los reclamos y tiene tope; clave hace idempotente el
-- encolado dentro de su organización y su tipo
CREATE TABLE lms.trabajo (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organizacion_id uuid        NOT NULL,
    tipo            text        NOT NULL,
    clave           text,
    datos           jsonb       NOT NULL DEFAULT '{}',
    estado          text        NOT NULL DEFAULT 'QUEUED' CHECK (estado IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    intentos        integer     NOT NULL DEFAULT 0 CHECK (intentos >= 0),
    max_intentos    integer     NOT NULL CHECK (max_intentos > 0),
    disponible_en   timestamptz NOT NULL,
    reclamado_en    timestamptz,
    error           text,
    resultado       jsonb,
    creado_en       timestamptz NOT NULL,
    terminado_en    timestamptz,
    UNIQUE (organizacion_id, tipo, clave)
);

-- lo que queda por hacer: el resto de la tabla es historia y no entra en el índice
CREATE INDEX trabajo_pendiente ON lms.trabajo (tipo, disponible_en) WHERE estado IN ('QUEUED', 'RUNNING');
