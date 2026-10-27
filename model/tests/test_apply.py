"""Pruebas de model/apply.py: validación sin red e idempotencia contra un Wasichai simulado en memoria.

El simulado imita solo el contrato que usa el script (docs/api/rest.md de Wasichai). Que ese contrato es el real lo
prueba la integración (LmsApiTest), que ejecuta este mismo script contra la aplicación.
"""

import copy
import json
import re
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apply  # noqa: E402

MODEL = {
    "enums": {"nivel": ["INICIAL", "AVANZADO"]},
    "objects": [
        {"name": "curso", "label": "Curso", "fields": [
            {"name": "titulo", "type": "TEXT", "required": True},
            {"name": "nivel", "type": "ENUM", "enum": "nivel", "defaultValue": "INICIAL"},
        ]},
        {"name": "version_curso", "label": "Versión", "actions": [{"name": "PUBLICAR", "label": "Publicar"}],
         "fields": [{"name": "numero", "type": "INTEGER", "required": True}],
         "uniqueConstraints": [["curso", "numero"]]},
    ],
    "relationships": [
        {"name": "version_curso_curso", "label": "Curso", "type": "MANY_TO_ONE", "source": "version_curso",
         "target": "curso", "fieldName": "curso", "required": True},
    ],
}
ROLES = {"roles": [{"name": "CREADOR", "label": "Creador", "permissions": [
    {"object": "curso", "actions": ["READ", "CREATE"]}, {"object": "version_curso", "actions": ["READ", "PUBLICAR"]}]}]}


class ValidationTest(unittest.TestCase):
    def problems(self, model, roles=None):
        try:
            apply.validate(model, roles)
        except apply.ModelError as error:
            return error.problems
        return []

    def test_valid_model_passes(self):
        self.assertEqual([], self.problems(MODEL, ROLES))

    def test_reports_every_problem_at_once(self):
        bad = copy.deepcopy(MODEL)
        bad["objects"][0]["fields"].append({"name": "id", "type": "TEXT"})
        bad["objects"][0]["fields"].append({"name": "Mal", "type": "TEXTO"})
        self.assertGreaterEqual(len(self.problems(bad)), 3)

    def test_relation_is_declared_as_relationship(self):
        bad = copy.deepcopy(MODEL)
        bad["objects"][0]["fields"].append({"name": "otro", "type": "RELATION"})
        self.assertTrue(any("relationships" in p for p in self.problems(bad)))

    def test_unknown_enum_and_bad_default(self):
        bad = copy.deepcopy(MODEL)
        bad["objects"][0]["fields"][1]["defaultValue"] = "EXPERTO"
        bad["objects"][0]["fields"].append({"name": "x", "type": "ENUM", "enum": "nada"})
        found = self.problems(bad)
        self.assertTrue(any("por defecto" in p for p in found))
        self.assertTrue(any("enum desconocido" in p for p in found))

    def test_declared_action_cannot_shadow_built_in(self):
        bad = copy.deepcopy(MODEL)
        bad["objects"][1]["actions"].append({"name": "READ"})
        self.assertTrue(any("reservado" in p for p in self.problems(bad)))

    def test_relationship_ends_and_duplicate_field(self):
        bad = copy.deepcopy(MODEL)
        bad["relationships"].append({"name": "r2", "type": "MANY_TO_ONE", "source": "version_curso", "target": "nada", "fieldName": "numero"})
        found = self.problems(bad)
        self.assertTrue(any("target desconocido" in p for p in found))
        self.assertTrue(any("ya existe" in p for p in found))

    def test_constraint_names_unknown_field(self):
        bad = copy.deepcopy(MODEL)
        bad["objects"][1]["uniqueConstraints"] = [["numero", "inexistente"]]
        self.assertTrue(any("inexistente" in p for p in self.problems(bad)))

    def test_role_grants_only_existing_actions(self):
        roles = copy.deepcopy(ROLES)
        roles["roles"][0]["permissions"][0]["actions"].append("PUBLICAR")
        roles["roles"].append({"name": "ADMIN", "label": "x", "permissions": []})
        found = self.problems(MODEL, roles)
        self.assertTrue(any("'PUBLICAR' no existe en 'curso'" in p for p in found))
        self.assertTrue(any("ADMIN" in p for p in found))


class FakeWasichai:
    """Estado en memoria y un registro de las escrituras recibidas."""

    def __init__(self):
        self.objects, self.actions, self.relationships, self.roles, self.writes = {}, {}, {}, {}, []

    def handle(self, method, path, body):
        if method != "GET":
            self.writes.append(f"{method} {path}")
        if (method, path) == ("POST", "/api/auth/login"):
            return 200, {"token": "t"} if body == {"email": "a@b.c", "password": "p"} else (401, {"detail": "bad credentials"})
        if (method, path) == ("GET", "/api/objects"):
            return 200, [{"name": n} for n in self.objects]
        if (method, path) == ("POST", "/api/objects"):
            self.objects[body["name"]] = copy.deepcopy(body)
            return 201, body
        if m := re.fullmatch(r"/api/metadata/objects/(\w+)", path):
            return 200, self.objects[m[1]]
        if m := re.fullmatch(r"/api/metadata/objects/(\w+)/fields", path):
            self.objects[m[1]]["fields"].append(body)
            return 201, body
        if m := re.fullmatch(r"/api/metadata/objects/(\w+)/fields/(\w+)", path):
            next(f for f in self.objects[m[1]]["fields"] if f["name"] == m[2]).update(body)
            return 200, {}
        if m := re.fullmatch(r"/api/metadata/objects/(\w+)/actions", path):
            if method == "GET":
                return 200, self.actions.get(m[1], [])
            self.actions.setdefault(m[1], []).append(body)
            return 201, body
        if m := re.fullmatch(r"/api/objects/(\w+)", path):
            self.objects[m[1]].update(body)
            return 200, {}
        if path == "/api/relationships":
            if method == "GET":
                return 200, list(self.relationships.values())
            self.relationships[body["name"]] = body
            owner = body["target"] if body["type"] == "ONE_TO_MANY" else body["source"]
            self.objects[owner]["fields"].append({"name": body["fieldName"], "type": "RELATION"})
            return 201, body
        if path == "/api/roles":
            if method == "GET":
                return 200, list(self.roles.values())
            self.roles[body["name"]] = {**body, "permissions": []}
            return 201, {}
        if m := re.fullmatch(r"/api/roles/(\w+)/permissions", path):
            self.roles[m[1]]["permissions"] = [{**p, "allowed": True} for p in body["permissions"]]
            return 200, {}
        return 404, {"detail": f"no route {method} {path}"}


class IdempotencyTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeWasichai()
        fake = self.fake

        class Handler(BaseHTTPRequestHandler):
            def _serve(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                status, answer = fake.handle(self.command, self.path, body)
                raw = json.dumps(answer).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            do_GET = do_POST = do_PUT = _serve

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.client = apply.Client(f"http://127.0.0.1:{self.server.server_port}")
        self.client.login("a@b.c", "p")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def run_once(self, model=MODEL, roles=ROLES):
        report = apply.Report()
        apply.apply_model(self.client, model, report)
        apply.apply_roles(self.client, roles, report)
        return report.changes

    def test_first_run_builds_everything_in_order(self):
        changes = self.run_once()
        self.assertIn("objeto curso", changes)
        self.assertIn("acción version_curso.PUBLICAR", changes)
        self.assertIn("obligatorio version_curso.curso", changes)
        self.assertIn("uniqueConstraints de version_curso", changes)
        # la restricción que nombra el campo de la relación espera a que la relación exista
        created = next(w for w in self.fake.writes if w == "POST /api/objects")
        self.assertNotIn("uniqueConstraints", self.fake.objects["curso"])
        self.assertTrue(created)
        self.assertEqual({("curso", "CREATE"), ("curso", "READ"), ("version_curso", "PUBLICAR"), ("version_curso", "READ")},
                         {(p["objectName"], p["action"]) for p in self.fake.roles["CREADOR"]["permissions"]})

    def test_second_run_changes_nothing(self):
        self.run_once()
        writes = len(self.fake.writes)
        self.assertEqual([], self.run_once())
        self.assertEqual(writes, len(self.fake.writes), "una segunda ejecución no debe escribir")

    def test_only_the_difference_is_sent(self):
        self.run_once()
        grown = copy.deepcopy(MODEL)
        grown["enums"]["nivel"].append("INTERMEDIO")
        grown["objects"][0]["fields"].append({"name": "resumen", "type": "LONG_TEXT"})
        changes = self.run_once(grown)
        # en el orden del modelo: nivel ya existía (gana una opción), resumen es nuevo
        self.assertEqual(["opciones curso.nivel: INTERMEDIO", "campo curso.resumen"], changes)

    def test_api_error_carries_problem_detail(self):
        with self.assertRaises(apply.ApiError) as raised:
            self.client.call("GET", "/api/nada")
        self.assertEqual(404, raised.exception.status)
        self.assertIn("no route", str(raised.exception))


class CommandLineTest(unittest.TestCase):
    def test_invalid_model_exits_2(self):
        tmp = Path(self.id().replace(".", "_") + ".json")
        try:
            tmp.write_text(json.dumps({"objects": [{"name": "Mal"}]}))
            self.assertEqual(2, apply.main(["--model", str(tmp), "--roles", "/nonexistent", "--validate-only"]))
        finally:
            tmp.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
