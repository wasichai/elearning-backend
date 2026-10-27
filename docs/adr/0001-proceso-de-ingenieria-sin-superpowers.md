# ADR-0001: El proceso de ingeniería no depende de Superpowers

**Estado**: aceptada · 2026-10-27

## Contexto

El proceso de trabajo se planteó en torno a Superpowers, un plugin de Claude Code que enseña brainstorming, desarrollo
dirigido por subagentes con revisión, depuración sistemática y TDD rojo→verde. En el entorno de este proyecto
(Claude Code 2.1.312, verificado el 2026-10-27):

- `~/.claude/plugins` solo registra el marketplace `claude-plugins-official` (`known_marketplaces.json`); no hay
  ningún plugin instalado.
- `superpowers` aparece en el catálogo de ese marketplace (`marketplaces/claude-plugins-official/.claude-plugin/marketplace.json`,
  fuente `obra/superpowers`), pero no está instalado ni hay skills suyas entre las disponibles.
- Sí están disponibles: plan mode, las skills `/code-review`, `/simplify` y `/security-review`, y subagentes que
  pueden trabajar en git worktrees aislados.

Ver [`reference-repositories.md` §8](../research/reference-repositories.md#8-entorno-local-verificado).

## Decisión

**El proceso se arma con lo que está instalado y no se simula Superpowers.**

| Necesidad                 | Cómo se cubre                                                                                                     |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Diseñar antes de escribir | plan mode; el plan aprobado se convierte en ADR y en documentos de `docs/`                                        |
| TDD                       | manual: prueba que falla (rojo), código mínimo que la pasa (verde), refactor; la prueba roja se ejecuta de verdad |
| Revisión de corrección    | `/code-review` sobre el diff de cada paso                                                                         |
| Simplificación            | `/simplify` antes de cerrar un paso                                                                               |
| Seguridad                 | `/security-review` en todo cambio que toque autenticación, autorización, entrada externa o archivos               |
| Paralelizar               | subagentes, cada uno en su worktree y su rama, con una lista cerrada de archivos que puede tocar                  |

**Trazabilidad en GitHub.** Una épica (issue) por tarea del plan; un issue y un PR por paso de la épica. El PR se
integra con squash-merge solo después de verificar: build, formato y pruebas ejecutados y en verde, revisión hecha.
Commits Conventional Commits en español, con `Refs #<issue>`.

## Alternativas consideradas

- **Instalar Superpowers desde el marketplace oficial.** Posible, pero cambia el entorno del usuario sin que lo haya
  pedido y añade una dependencia del proceso que un colaborador externo no tiene por qué compartir. No se descarta: si
  se instala, se revisa este ADR.
- **Imitar sus skills con prompts propios.** Rechazado: sería afirmar un proceso que no existe; la especificación pide
  no simular herramientas.
- **Sin proceso explícito.** Rechazado: con varios agentes en paralelo, la falta de reglas (ramas, archivos asignados,
  verificación antes de integrar) produce conflictos y cambios sin probar.

## Consecuencias

- El proceso es reproducible por cualquier colaborador con Claude Code o sin él: las reglas son git, issues, PR y
  pruebas, no un plugin.
- El TDD depende de disciplina: nada obliga técnicamente a escribir la prueba primero. La revisión de cada PR comprueba
  que el comportamiento nuevo tiene prueba.
- Los subagentes en paralelo cuestan coordinación: el agente principal reparte archivos sin solapes, integra y mantiene
  los archivos compartidos (`README.md`, `CLAUDE.md`, `docs/HISTORY.md`).
- Una épica y un issue por paso generan más tickets que un único PR grande; a cambio, cada cambio es pequeño y revisable.
- Abierto: si el usuario instala Superpowers, un ADR nuevo decide qué partes del proceso adopta.
