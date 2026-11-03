-- la supresión de datos personales (ADR-0037, V15) también disocia lms.ejecucion_agente: quien la pidió y quien intervino
-- pasan al seudónimo. la ejecución se queda, es la evidencia del proceso editorial. el disparador lo deja pasar solo dentro
-- de la transacción de una supresión, que lo declara con set_config('lms.supresion', 'on', true), y solo si no cambia nada
-- más: ni otro campo, ni el número ni el contenido de las intervenciones salvo su userId. borrar sigue prohibido
CREATE OR REPLACE FUNCTION lms.ejecucion_agente_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    cierre CONSTANT text[] := ARRAY['estado', 'salida', 'validaciones', 'error', 'tokens_entrada', 'tokens_salida', 'coste',
        'moneda', 'tarifa_entrada', 'tarifa_salida', 'tarifa_desde', 'terminada_en'];
    previas integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'lms.ejecucion_agente no se borra';
    END IF;
    previas := jsonb_array_length(OLD.intervenciones);
    IF current_setting('lms.supresion', true) = 'on'
        AND (to_jsonb(NEW) - 'solicitante_id' - 'intervenciones') = (to_jsonb(OLD) - 'solicitante_id' - 'intervenciones')
        AND jsonb_array_length(NEW.intervenciones) = previas
        AND NOT EXISTS (SELECT 1 FROM generate_series(0, previas - 1) AS i
                        WHERE (NEW.intervenciones -> i) - 'userId' IS DISTINCT FROM (OLD.intervenciones -> i) - 'userId') THEN
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - cierre - 'intervenciones') IS DISTINCT FROM (to_jsonb(OLD) - cierre - 'intervenciones') THEN
        RAISE EXCEPTION 'lo que se pidió a una ejecución de agente no cambia';
    END IF;
    IF (to_jsonb(NEW) - 'intervenciones') IS DISTINCT FROM (to_jsonb(OLD) - 'intervenciones')
        AND OLD.estado NOT IN ('PENDING', 'RUNNING') THEN
        RAISE EXCEPTION 'una ejecución de agente terminada no se vuelve a cerrar';
    END IF;
    IF jsonb_array_length(NEW.intervenciones) < previas
        OR EXISTS (SELECT 1 FROM generate_series(0, previas - 1) AS i WHERE NEW.intervenciones -> i IS DISTINCT FROM OLD.intervenciones -> i) THEN
        RAISE EXCEPTION 'las intervenciones de una ejecución de agente solo se añaden';
    END IF;
    RETURN NEW;
END;
$$;
