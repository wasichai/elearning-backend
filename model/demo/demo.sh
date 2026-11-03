#!/usr/bin/env bash
# La demo poblada de wasichai-learning, de principio a fin (model/demo/README.md). Se corre desde cualquier lugar:
#
#   model/demo/demo.sh arriba            levanta la base y el buzón de la demo (compose.demo.yml, proyecto elearning-demo)
#   model/demo/demo.sh backend           arranca el backend de la demo (:8096) contra elearning_demo, en primer plano
#   model/demo/demo.sh sembrar [--validate-only]   aplica el modelo, los roles, data.json y poblada.json
#   model/demo/demo.sh cuentas           lista las cuentas de la demo y su clave
#   model/demo/demo.sh abajo [--borrar]  detiene los contenedores; --borrar elimina también la base
#
# Las variables salen de model/demo/demo.env (copia de demo.env.example) o, si no existe, del ejemplo. Ningún comando
# toca otra base que elearning_demo* en el puerto de la demo, ni otro backend que el de localhost en el puerto de la demo.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
env_file="$here/demo.env"
[ -f "$env_file" ] || env_file="$here/demo.env.example"
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

# la demo es la base elearning_demo* en el puerto de la demo y su backend en localhost: nunca los de desarrollo
guard() {
  case "${WASICHAI_DB_NAME:-}" in elearning_demo*) ;; *) echo "demo: WASICHAI_DB_NAME=${WASICHAI_DB_NAME:-} no es una base de la demo" >&2; exit 2 ;; esac
  if [ "${WASICHAI_DB_PORT:-}" != "${LMS_DEMO_PG_PORT:-5446}" ]; then
    echo "demo: WASICHAI_DB_PORT=${WASICHAI_DB_PORT:-} no es el puerto de la demo (${LMS_DEMO_PG_PORT:-5446})" >&2
    exit 2
  fi
  case "${WASICHAI_URL:-}" in
    http://localhost:"${PORT:-}" | http://127.0.0.1:"${PORT:-}") ;;
    *) echo "demo: WASICHAI_URL=${WASICHAI_URL:-} no es el backend de la demo (http://localhost:${PORT:-})" >&2; exit 2 ;;
  esac
  if [ "${PORT:-}" = "8090" ]; then
    echo "demo: el 8090 es el backend de desarrollo" >&2
    exit 2
  fi
}

compose() { docker compose -p elearning-demo -f "$here/compose.demo.yml" "$@"; }

order=${1:-}
shift || true
case "$order" in
  arriba)
    guard
    compose up -d --wait
    echo "demo: base en 127.0.0.1:${LMS_DEMO_PG_PORT:-5446}, buzón en http://127.0.0.1:8046"
    echo "demo: ahora, en otra terminal, model/demo/demo.sh backend"
    ;;
  backend)
    guard
    cd "$repo"
    # bootRun lee LMS_ENV_FILE en lugar del .env de desarrollo (build.gradle.kts)
    LMS_ENV_FILE="$env_file" exec ./gradlew bootRun
    ;;
  sembrar)
    guard
    cd "$repo"
    exec python3 model/apply.py --demo --demo-data model/demo/data.json --demo-data model/demo/poblada.json "$@"
    ;;
  cuentas)
    python3 - "$here" "${WASICHAI_DEMO_PASSWORD:-}" <<'PY'
import json, sys
from pathlib import Path
here, password = Path(sys.argv[1]), sys.argv[2]
print(f"Clave de todas (WASICHAI_DEMO_PASSWORD): {password}\nAdministrador: admin@wasichai.local / admin\n")
for name in ("data.json", "poblada.json"):
    for user in json.loads((here / name).read_text(encoding="utf-8"))["users"]:
        print(f"  {', '.join(user['roles']):<18} {user['email']:<46} {user['displayName']}")
PY
    ;;
  abajo)
    if [ "${1:-}" = "--borrar" ]; then
      echo "demo: se borra el volumen de elearning-demo (la base de la demo)"
      compose down -v
      rm -rf "$repo/build/demo"
    else
      compose down
    fi
    ;;
  *)
    sed -n '2,10p' "$0"
    exit 1
    ;;
esac
