-- el barrido de intentos vencidos (L4 de #183) busca cada minuto los abiertos con plazo: solo esos entran en el índice
CREATE INDEX intento_abierto_vence ON lms.intento (vence_en) WHERE estado = 'OPEN' AND vence_en IS NOT NULL;
