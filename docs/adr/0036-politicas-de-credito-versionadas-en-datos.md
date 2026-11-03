# ADR-0036: Las políticas de crédito son datos versionados; los métodos, código

**Estado**: aceptada · 2026-11-03 · complementa [ADR-0007](0007-carga-academica-y-creditos-como-politica-versionada.md) y
[ADR-0035](0035-aprobacion-de-creditos-por-una-autoridad-academica.md) · RF-CRE-07 ·
[#269](https://github.com/wasichai/elearning-backend/issues/269), [#318](https://github.com/wasichai/elearning-backend/issues/318)

## Contexto

- ADR-0007 pide políticas de crédito configurables por programa, institución y jurisdicción, publicadas y versionadas, y
  dejó en F1 dos políticas de referencia en código (`EctsPolicy`, `SemesterCreditHourPolicy`). ADR-0035 aprueba con la
  política de referencia mientras no haya otras.
- La investigación de #269 muestra que cada jurisdicción fija un método distinto (ECTS: carga total a 25–30 h; EE. UU.:
  34 CFR 600.2; Perú, Ley 30220 art. 39: 16 h lectivas de teoría o 32 de práctica como mínimo, sin estudio autónomo) y que
  dentro de él cada institución o programa elige sus parámetros. Las normas cambian: cada política lleva su fuente y su
  vigencia.

## Decisión

1. **Método en código, política en datos.** Los métodos son funciones puras de `elearning.workload`, cada una de un
   sistema: `ECTS_CARGA_TOTAL`, `US_SCH_34CFR600_2` y el nuevo `PE_LEY30220_HORAS_LECTIVAS` (teoría ÷ horas por crédito de
   teoría + práctica ÷ horas por crédito de práctica, redondeo hacia abajo al paso; ni autónomas ni supervisadas, que solo
   avisan). `CreditMethods.of(método, parámetros)` valida contra la norma: ECTS fuera de 25–30 h, PE bajo 16/32 h, un
   parámetro desconocido o que no es un número → `400`. SATCA y SCT quedan para otro issue, tras verificar sus fuentes
   primarias.
2. **`CreditSystem` gana `PE_CREDITO`.** Sigue sin haber conversión entre sistemas (RF-CRE-06).
3. **`politica_credito`** (Custom Object): `clave` y `numero` (único juntos; `version` está reservado en Wasichai),
   `nombre`, `estado` (`BORRADOR`, `PUBLICADA`, `RETIRADA`), `sistema`, `metodo`, `parametros` (JSON; vacío, los de la
   norma), `jurisdiccion` (ISO 3166-1 alfa-2 o `EHEA`), `programa_codigo` opcional, `fuente`, `fuente_url`,
   `vigente_desde`, `publicada_por`, `publicada_en`. **Ámbito**: la institución es la organización de Wasichai; el
   programa, un código opcional (sin objeto `programa` hasta un segundo uso). El ámbito sirve para encontrarla; qué
   política usa una versión lo decide la relación del punto 5.
4. **`CreditPolicyGuard`**: una política nace y se edita en borrador; su estado y su publicación solo los cambian los
   comandos de publicar y retirar; publicada o retirada no cambia ni se borra (`409`, también para ADMIN). Cambiarla es
   `POST /api/lms/credits/policies/{id}/new-version`: un borrador con `numero + 1` y lo mismo que la de origen, que no se
   toca. Publicar y retirar exigen la acción `PUBLICAR` sobre `politica_credito` (AUTORIDAD_ACADEMICA).
5. **`version_curso.politica_credito`**, una relación a una versión concreta de política, es contenido de la versión: la
   pone el autor en borrador y se congela con ella. La versión propone con esa política si es de su sistema, con la de
   referencia de su sistema si no tiene ninguna, y no propone si está ligada a otro sistema. Publicar una política nueva
   no cambia lo ya ligado. Aprobar (ADR-0035) exige la ligada **publicada** y de su sistema (`409`) y guarda su
   instantánea y la relación en `aprobacion_creditos`.
6. `politica_creditos` (texto `id@versión`) de `version_curso` se queda: es la copia de la política de la aprobación
   vigente (ADR-0035), ahora `clave@numero`. `model/apply.py` solo añade, y quitarlo no ganaría nada.

## Alternativas consideradas

- **Un motor de reglas genérico** (fórmulas en datos). Rechazada: cada método tiene comprobaciones normativas que no son
  aritmética (mínimos legales, avisos) y no hay un segundo uso (regla 8).
- **Políticas en configuración** (`application.yml`). Rechazada: no se versionan ni se publican con quién y cuándo, y una
  institución no las cambia sin desplegar.
- **Ligar la política al curso o al programa, no a la versión.** Rechazada: la versión es lo que se publica y se aprueba;
  ligarla allí es lo que hace que una política nueva no cambie lo ya propuesto.
- **Elegir la política por ámbito al proponer** (la vigente de la jurisdicción y el programa). Rechazada por ahora: haría
  depender lo propuesto de la fecha y de qué otras políticas existan; la relación explícita lo deja quieto.

## Consecuencias

- Una propuesta y una aprobación nombran siempre una versión de política; la de una versión publicada no cambia.
- Perú se calcula con el mínimo legal; si una universidad cuenta más horas por crédito, publica su propia política.
- La equivalencia en modalidad a distancia y la de las horas supervisadas son decisiones institucionales, no cálculo:
  el método avisa y no suma.
- Abierto: cuatro ojos al publicar una política (#269).
