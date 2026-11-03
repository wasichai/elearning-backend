-- la validación de borradores (RF-IA-05, docs/ai §13) busca las ejecuciones de una versión: la última validación (paso
-- VALIDATION, la versión en version_curso_id) al leer el informe y al enviar a revisión, y si un paso que crea borradores
-- la nombra, en version_curso_id o en salida.versionId (la versión que creó la ejecución)
CREATE INDEX ejecucion_agente_version
    ON lms.ejecucion_agente (organizacion_id, version_curso_id, paso, terminada_en DESC)
    WHERE version_curso_id IS NOT NULL;

CREATE INDEX ejecucion_agente_version_creada
    ON lms.ejecucion_agente (organizacion_id, (salida ->> 'versionId'))
    WHERE salida ->> 'versionId' IS NOT NULL;
