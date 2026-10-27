# ADR-0008: La API propia se documenta con OpenAPI vía springdoc 3.x

**Estado**: aceptada · 2026-10-27

## Contexto

- La especificación pide «OpenAPI compatible con Boot 4».
- Wasichai no tiene OpenAPI (verificado: sin springdoc ni anotaciones); su API se documenta a mano en
  `docs/api/rest.md`.
- springdoc-openapi 3.2.0 es la línea para Spring Boot 4; no hay precedente de usarlo junto a Wasichai, Jackson 3 y su
  `SecurityWebFilterChain` ([`reference-repositories.md` §1–§2](../research/reference-repositories.md)).
- La API de Wasichai (`/api/metadata/**`, `/api/records/**`…) no es de este proyecto; describirla aquí sería duplicar
  y desincronizar su documentación.

## Decisión

- **springdoc-openapi 3.x** (`springdoc-openapi-starter-webflux-api`, versión en el catálogo de versiones) genera el
  documento OpenAPI de este proyecto.
- **Alcance limitado a `/api/lms/**`** (grupo de springdoc con `pathsToMatch`); la API de Wasichai se enlaza a su
  documentación, no se describe.
- El documento se sirve en `/api/lms/openapi` (ruta a confirmar en implementación); la interfaz Swagger UI no se
  incluye por defecto.
- Los DTO llevan las anotaciones mínimas para que el contrato sea correcto (nulabilidad, ejemplos donde aclaren).
- **Plan B**: si springdoc choca con la configuración de Wasichai (cadena de seguridad, Jackson 3, manejo de errores) y
  no se resuelve sin tocar Wasichai, se documenta el conflicto en este repositorio, se retira la dependencia y la API
  propia se describe a mano en `docs/api/lms.md`, como hace Wasichai. Un ADR nuevo registra el cambio.

## Alternativas consideradas

- **Documentación a mano, como Wasichai.** Es el plan B: sin dependencias, pero no genera clientes ni valida el
  contrato contra el código.
- **Contract-first (escribir el YAML y generar código).** Rechazado ahora: añade generación de código para una API
  pequeña.
- **Documentar también la API de Wasichai.** Rechazado: no es de este proyecto.

## Consecuencias

- El frontend puede comprobar sus esquemas Zod contra el contrato generado (a decidir en F1 si se automatiza).
- Una dependencia más, en una combinación sin precedentes: el riesgo de choque es real y el plan B está escrito.
- El documento es público solo si se decide; por defecto queda tras la cadena de Wasichai (requiere sesión).
- A verificar en implementación: compatibilidad de springdoc 3.2.0 con Spring Boot 4.1.1 y Jackson 3, y cómo
  representa `ProblemDetail` y las funciones `suspend`.
