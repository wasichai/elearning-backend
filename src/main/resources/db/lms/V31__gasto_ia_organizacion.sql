-- el presupuesto de IA de la organización por período (#368, ADR-0033 §9): lo gastado desde el comienzo del período, entre
-- todos sus cursos, se suma en una consulta antes de encolar y antes de cada llamada. solo las ejecuciones con coste
CREATE INDEX ejecucion_agente_gasto
    ON lms.ejecucion_agente (organizacion_id, iniciada_en)
    INCLUDE (coste, moneda)
    WHERE coste IS NOT NULL;
