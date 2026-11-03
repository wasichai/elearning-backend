-- trabajos de IA (ADR-0033 §5, E33.2): la cola gana el estado CANCELLED. cancelar marca el trabajo, pendiente o en curso, y
-- lo da por terminado: nadie lo reclama, su manejador lo ve entre llamadas al modelo y su cierre ya no puede escribir. como
-- un terminado, libera su clave (el índice parcial trabajo_clave_activa sigue cubriendo solo QUEUED y RUNNING) y la
-- retención lo borra
ALTER TABLE lms.trabajo DROP CONSTRAINT trabajo_estado_check;

ALTER TABLE lms.trabajo ADD CONSTRAINT trabajo_estado_check CHECK (estado IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED'));

-- reencolar un paso ya resuelto devuelve su ejecución sin otra llamada al proveedor: se busca por su clave
CREATE INDEX ejecucion_agente_clave ON lms.ejecucion_agente (organizacion_id, clave) WHERE estado = 'SUCCEEDED';
