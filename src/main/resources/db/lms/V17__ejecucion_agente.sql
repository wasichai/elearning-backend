-- el registro AgentRun (RF-IA-03, ADR-0033 §7, docs/ai §4): una fila por ejecución de un paso de IA, ligada a su trabajo
-- de lms.trabajo, que la cola purga. por eso trabajo_id no es clave foránea: la ejecución se queda, es la trazabilidad de
-- lo generado. sin claves ni cabeceras en ningún campo; parametros_modelo solo lleva los parámetros efectivos del paso
CREATE TABLE lms.ejecucion_agente (
    id                    uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    organizacion_id       uuid           NOT NULL,
    trabajo_id            uuid           NOT NULL,
    paso                  text           NOT NULL CHECK (length(btrim(paso)) > 0),
    -- el trabajo corre como la plataforma: quien lo pidió queda aquí
    solicitante_id        uuid           NOT NULL,
    curso_id              uuid           NOT NULL,
    unidad_id             uuid,
    version_curso_id      uuid,
    objetivo              text           NOT NULL CHECK (length(btrim(objetivo)) > 0),
    entradas              jsonb          NOT NULL,
    -- [{ "ref": "owner/repo@sha:ruta#ancla", "sha256" }]
    fuentes               jsonb          NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(fuentes) = 'array'),
    generador             text           NOT NULL CHECK (generador IN ('MODEL', 'DETERMINISTIC')),
    proveedor             text           CHECK (proveedor IN ('anthropic', 'openai')),
    modelo                text,
    parametros_modelo     jsonb,
    version_instrucciones text           NOT NULL,
    version_plantilla     text           NOT NULL,
    clave                 text           NOT NULL,
    intento               integer        NOT NULL CHECK (intento > 0),
    iniciada_en           timestamptz    NOT NULL,
    -- el cierre de la ejecución: se escribe una vez, al terminar
    estado                text           NOT NULL DEFAULT 'RUNNING'
        CHECK (estado IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'INVALID_OUTPUT', 'FAILED', 'CANCELLED')),
    salida                jsonb,
    validaciones          jsonb,
    error                 text,
    tokens_entrada        integer        CHECK (tokens_entrada >= 0),
    tokens_salida         integer        CHECK (tokens_salida >= 0),
    -- el coste con la tarifa configurada para el proveedor y el modelo (por millón de tokens), que se guarda con su fecha:
    -- cambiar la tarifa no reescribe lo ya gastado
    coste                 numeric(14, 6) CHECK (coste >= 0),
    moneda                text,
    tarifa_entrada        numeric(14, 6),
    tarifa_salida         numeric(14, 6),
    tarifa_desde          date,
    terminada_en          timestamptz,
    -- [{ "userId", "kind": "ACCEPTED" | "EDITED" | "REJECTED", "at", "note" }]: solo se añaden
    intervenciones        jsonb          NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(intervenciones) = 'array'),
    -- un modelo dice cuál y con qué; el doble determinista no tiene ninguno
    CHECK (CASE generador
               WHEN 'MODEL' THEN proveedor IS NOT NULL AND modelo IS NOT NULL AND parametros_modelo IS NOT NULL
               ELSE proveedor IS NULL AND modelo IS NULL AND parametros_modelo IS NULL END),
    CHECK ((estado IN ('PENDING', 'RUNNING')) = (terminada_en IS NULL)),
    CHECK (coste IS NULL OR (moneda IS NOT NULL AND tarifa_entrada IS NOT NULL AND tarifa_salida IS NOT NULL AND tarifa_desde IS NOT NULL))
);

-- la lectura es por curso, la más reciente primero
CREATE INDEX ejecucion_agente_curso ON lms.ejecucion_agente (organizacion_id, curso_id, iniciada_en DESC);

-- lo que se pidió no cambia y nada se borra. una ejecución en curso se cierra una vez (estado, salida, consumo, coste y
-- fin); las intervenciones se añaden al final, sin tocar las anteriores. protege de errores, también con SQL directo
CREATE FUNCTION lms.ejecucion_agente_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    cierre CONSTANT text[] := ARRAY['estado', 'salida', 'validaciones', 'error', 'tokens_entrada', 'tokens_salida', 'coste',
        'moneda', 'tarifa_entrada', 'tarifa_salida', 'tarifa_desde', 'terminada_en'];
    previas integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'lms.ejecucion_agente no se borra';
    END IF;
    IF (to_jsonb(NEW) - cierre - 'intervenciones') IS DISTINCT FROM (to_jsonb(OLD) - cierre - 'intervenciones') THEN
        RAISE EXCEPTION 'lo que se pidió a una ejecución de agente no cambia';
    END IF;
    IF (to_jsonb(NEW) - 'intervenciones') IS DISTINCT FROM (to_jsonb(OLD) - 'intervenciones')
        AND OLD.estado NOT IN ('PENDING', 'RUNNING') THEN
        RAISE EXCEPTION 'una ejecución de agente terminada no se vuelve a cerrar';
    END IF;
    previas := jsonb_array_length(OLD.intervenciones);
    IF jsonb_array_length(NEW.intervenciones) < previas
        OR EXISTS (SELECT 1 FROM generate_series(0, previas - 1) AS i WHERE NEW.intervenciones -> i IS DISTINCT FROM OLD.intervenciones -> i) THEN
        RAISE EXCEPTION 'las intervenciones de una ejecución de agente solo se añaden';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ejecucion_agente_inmutable
    BEFORE UPDATE OR DELETE ON lms.ejecucion_agente
    FOR EACH ROW EXECUTE FUNCTION lms.ejecucion_agente_inmutable();
