"""Datos de demostración (model/demo/data.json) y la fase --demo de model/apply.py.

Sin red: el contenido se valida contra el modelo real, y la aplicación se prueba contra un Wasichai simulado en memoria
que imita solo el contrato que usa el script (el mismo patrón que test_apply.py, copiado para no acoplar los archivos).
"""

import contextlib
import copy
import io
import json
import os
import re
import sys
import threading
import unittest
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import apply  # noqa: E402

MODEL = apply.load(HERE / "model.json")
ROLES = apply.load(HERE / "roles.json")
DATA = apply.load(HERE / "demo" / "data.json")

# Anclas comprobadas a mano en los archivos de cada commit (gh api …/contents/<ruta>?ref=<sha>). Las de
# 05-compromisos.adoc que no son usr-compromisos no están escritas en el archivo: son los id que genera Asciidoctor con
# los atributos del manual (idprefix vacío, idseparator «-»), y ningún otro título del manual de usuario las repite.
VERIFIED_ANCHORS = {
    "wasichai/srtm-backend@6daacd3:docs/manuales/comun/acceso.adoc": {"acceso", "requisitos-acceso", "ingresar", "proc-ingresar", "sesion", "cerrar-sesion"},
    "wasichai/srtm-backend@6daacd3:docs/manuales/usuario/anuncios.adoc": {
        "presentacion-leer", "anuncio-conceptos", "estados", "proc-padron", "padron-a-fecha", "proc-nuevo", "reintento", "proc-renovar",
        "renovacion-reglas", "proc-cesar", "proc-retirar", "tasa-como-aplica", "devengado", "fichas", "errores", "faq-anuncios"},
    "wasichai/sgspe@0ebf69f:docs/manuals/user/02-primeros-pasos.adoc": {
        "usr-primeros-pasos", "usr-login", "usr-proyecto-360", "usr-listas", "usr-fichas", "usr-transiciones", "usr-mensajes"},
    "wasichai/sgspe@0ebf69f:docs/manuals/user/05-compromisos.adoc": {
        "usr-compromisos", "consultar-compromisos", "registrar-un-compromiso", "la-ficha-del-compromiso", "aprobar",
        "registrar-una-entrega-y-el-cumplimiento", "reprogramar-bloquear-e-informar-retrasos", "candidatos-a-compromiso", "acuerdos-y-convenios"},
}
EXPECTED_COURSES = {"SRTM-ANU-101": "srtm", "SGSPE-101": "sgspe", "IA-AGT-201": None, "SRTM-ATC-001": None}
DRAFT_ONLY = "SRTM-ATC-001"


def records(obj):
    return [r for r in DATA["records"] if r["object"] == obj]


def by_ref():
    return {r["ref"]: r for r in DATA["records"]}


def children(obj, field, parent_ref):
    return [r for r in records(obj) if r.get("refs", {}).get(field) == parent_ref]


def versions_of(course_code):
    course = next(r for r in records("curso") if r["attributes"]["codigo"] == course_code)
    return children("version_curso", "curso", course["ref"])


def problems(data):
    try:
        apply.validate_demo(data, MODEL, ROLES)
    except apply.ModelError as error:
        return error.problems
    return []


class DemoDataTest(unittest.TestCase):
    def test_data_is_valid_against_the_real_model(self):
        self.assertEqual([], problems(DATA))

    def test_every_ref_resolves_to_an_earlier_record_of_the_right_object(self):
        seen = {}
        relations = {(r["source"], r["fieldName"]): r["target"] for r in MODEL["relationships"]}
        for record in DATA["records"]:
            for field, target in record.get("refs", {}).items():
                self.assertIn(target, seen, f"{record['ref']}.{field}")
                self.assertEqual(relations[(record["object"], field)], seen[target], f"{record['ref']}.{field}")
            seen[record["ref"]] = record["object"]
        emails = {u["email"] for u in DATA["users"]}
        for record in DATA["records"]:
            for email in record.get("users", {}).values():
                self.assertIn(email, emails)

    def test_exactly_the_expected_courses_and_one_draft_only(self):
        self.assertEqual(set(EXPECTED_COURSES), {r["attributes"]["codigo"] for r in records("curso")})
        for code in EXPECTED_COURSES:
            published = [v for v in versions_of(code) if v["attributes"]["estado"] == "PUBLICADA"]
            self.assertEqual(0 if code == DRAFT_ONLY else 1, len(published), code)
        self.assertEqual({"BORRADOR"}, {v["attributes"]["estado"] for v in versions_of(DRAFT_ONLY)})

    def test_course_a_has_a_draft_v2_besides_the_published_v1(self):
        states = {v["attributes"]["numero"]: v["attributes"]["estado"] for v in versions_of("SRTM-ANU-101")}
        self.assertEqual({1: "PUBLICADA", 2: "BORRADOR"}, states)

    def test_published_versions_are_complete_and_marked_illustrative(self):
        for version in records("version_curso"):
            self.assertIs(True, version["attributes"]["contenido_ilustrativo"], version["ref"])
            if version["attributes"]["estado"] != "PUBLICADA":
                continue
            for field in ("objetivos", "resultados_aprendizaje", "requisitos", "sistema_evaluacion", "certificacion", "fuentes", "publicada_en"):
                self.assertTrue(version["attributes"].get(field), f"{version['ref']}.{field}")
            units = children("unidad", "version_curso", version["ref"])
            self.assertTrue(2 <= len(units) <= 4, version["ref"])
            for unit in units:
                self.assertTrue(2 <= len(children("leccion", "unidad", unit["ref"])) <= 4, unit["ref"])

    def test_lessons_of_courses_a_and_b_cite_verified_anchors(self):
        refs = by_ref()
        cited = 0
        for lesson in records("leccion"):
            source = lesson["attributes"].get("referencia_fuente")
            unit = refs[lesson["refs"]["unidad"]]
            course = refs[refs[unit["refs"]["version_curso"]]["refs"]["curso"]]
            if EXPECTED_COURSES[course["attributes"]["codigo"]] is not None:
                self.assertTrue(source, f"{lesson['ref']}: los cursos A y B citan su fuente")
            if source:
                path, _, anchor = source.partition("#")
                self.assertIn(path, VERIFIED_ANCHORS, lesson["ref"])
                self.assertIn(anchor, VERIFIED_ANCHORS[path], lesson["ref"])
                cited += 1
        self.assertGreaterEqual(cited, 20)

    def test_course_c_carries_the_specification_workload_without_approved_credits(self):
        version = next(v for v in versions_of("IA-AGT-201") if v["attributes"]["estado"] == "PUBLICADA")["attributes"]
        self.assertEqual((6, 12, 12, 12, "ECTS"), (version["semanas"], version["horas_teoria_semana"], version["horas_practica_semana"],
                                                  version["horas_autonomas_semana"], version["sistema_credito"]))
        for v in records("version_curso"):
            self.assertNotIn("creditos_aprobados", v["attributes"], "los créditos aprobados los pone una persona")

    def test_editions_are_future_in_lima_and_only_of_published_versions(self):
        refs = by_ref()
        per_course = {}
        for edition in records("edicion_curso"):
            a = edition["attributes"]
            version = refs[edition["refs"]["version_curso"]]
            self.assertEqual("PUBLICADA", version["attributes"]["estado"], edition["ref"])
            self.assertEqual("America/Lima", a["zona_horaria"])
            self.assertTrue("2026-11-01" <= a["fecha_inicio"] < a["fecha_fin"] <= "2027-06-30", edition["ref"])
            self.assertTrue(a["inscripcion_desde"] < a["inscripcion_hasta"] <= a["fecha_inicio"], edition["ref"])
            self.assertGreater(a["capacidad"], 0)
            self.assertTrue(children("docente_edicion", "edicion_curso", edition["ref"]), f"{edition['ref']} sin docente")
            course = version["refs"]["curso"]
            per_course[course] = per_course.get(course, 0) + 1
        self.assertEqual(3, len(per_course))
        self.assertTrue(all(1 <= n <= 2 for n in per_course.values()))

    def test_one_user_per_role_and_one_multi_role(self):
        role_sets = [tuple(sorted(u["roles"])) for u in DATA["users"]]
        for role in ("ESTUDIANTE", "DOCENTE", "CREADOR", "REVISOR"):
            self.assertIn((role,), role_sets)
        self.assertIn(("DOCENTE", "REVISOR"), role_sets)
        self.assertTrue(all(u["email"].endswith("@demo.wasichai-learning.test") and "password" not in u for u in DATA["users"]))

    def test_no_real_data_from_the_manuals(self):
        text = json.dumps(DATA, ensure_ascii=False)
        for forbidden in ("Perené", "Perene", "Municipalidad Distrital", "20999000020", "95000077", "Wayra", "wasichai.local", "RUC", "DNI"):
            self.assertNotIn(forbidden, text)


class DemoValidationTest(unittest.TestCase):
    def broken(self, change):
        data = copy.deepcopy(DATA)
        change(data)
        return problems(data)

    def record(self, data, ref):
        return next(r for r in data["records"] if r["ref"] == ref)

    def test_reports_every_problem_at_once(self):
        def change(data):
            self.record(data, "version-srtm-1")["attributes"]["estado"] = "VIGENTE"
            self.record(data, "curso-srtm")["attributes"]["inventado"] = "x"
            data["users"][0]["roles"].append("SUPERUSUARIO")
            data["users"][1]["password"] = "secreto"
        found = self.broken(change)
        self.assertTrue(any("'VIGENTE' no es una opción" in p for p in found))
        self.assertTrue(any("campo desconocido 'inventado'" in p for p in found))
        self.assertTrue(any("rol desconocido 'SUPERUSUARIO'" in p for p in found))
        self.assertTrue(any("contraseña no va en el archivo" in p for p in found))

    def test_refs_must_exist_earlier_and_point_to_the_right_object(self):
        def change(data):
            self.record(data, "version-srtm-1")["refs"]["curso"] = "cat-ia-aplicada"
            self.record(data, "srtm-v1-u1")["refs"]["version_curso"] = "no-existe"
            self.record(data, "perfil-diego")["users"]["usuario_id"] = "nadie@demo.wasichai-learning.test"
        found = self.broken(change)
        self.assertTrue(any("apunta a un categoria, no a un curso" in p for p in found))
        self.assertTrue(any("'no-existe', que no está antes" in p for p in found))
        self.assertTrue(any("'nadie@demo.wasichai-learning.test', que no está en 'users'" in p for p in found))

    def test_keys_must_be_unique_in_the_model_and_in_the_file(self):
        def change(data):
            self.record(data, "curso-ia")["attributes"]["codigo"] = "SRTM-ANU-101"
            self.record(data, "cat-ia-aplicada")["key"] = ["nombre"]
        found = self.broken(change)
        self.assertTrue(any("clave natural repetida" in p for p in found))
        self.assertTrue(any("no es un campo único ni una uniqueConstraint" in p for p in found))

    def test_required_fields_types_and_domain(self):
        def change(data):
            del self.record(data, "curso-srtm")["attributes"]["titulo"]
            del self.record(data, "srtm-v1-u1-l1")["refs"]
            self.record(data, "edicion-ia-2027-04")["attributes"]["fecha_inicio"] = "05/04/2027"
            self.record(data, "version-ia-1")["attributes"]["publicada_en"] = "2026-10-22T16:00:00"
            data["users"][0]["email"] = "alguien@ejemplo.com"
        found = self.broken(change)
        self.assertTrue(any("falta el campo obligatorio 'titulo'" in p for p in found))
        self.assertTrue(any("falta la relación obligatoria 'unidad'" in p for p in found))
        self.assertTrue(any("'05/04/2027' no es un DATE" in p for p in found))
        self.assertTrue(any("lleva zona horaria" in p for p in found))
        self.assertTrue(any("dominio demo.wasichai-learning.test" in p for p in found))

    def test_validate_only_with_demo_rejects_bad_data(self):
        tmp = Path(self.id().replace(".", "_") + ".json")
        try:
            data = copy.deepcopy(DATA)
            data["records"][0]["object"] = "inexistente"
            tmp.write_text(json.dumps(data))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(2, apply.main(["--validate-only", "--demo", "--demo-data", str(tmp)]))
            self.assertIn("objeto desconocido", err.getvalue())
        finally:
            tmp.unlink(missing_ok=True)


class FakeWasichai:
    """Estado en memoria y un registro de las escrituras recibidas. Rechaza lo que el servidor real rechazaría."""

    def __init__(self):
        self.objects, self.actions, self.relationships, self.roles, self.writes = {}, {}, {}, {}, []
        self.users, self.records = {}, {}

    def handle(self, method, raw_path, body):
        url = urllib.parse.urlsplit(raw_path)
        path, query = url.path, dict(urllib.parse.parse_qsl(url.query))
        if method != "GET":
            self.writes.append(f"{method} {path}")
        if (method, path) == ("POST", "/api/auth/login"):
            return (200, {"token": "t"}) if body == {"email": "a@b.c", "password": "p"} else (401, {"detail": "bad credentials"})
        if path == "/api/users":
            if method == "GET":
                return 200, [{k: v for k, v in u.items() if k != "password"} for u in self.users.values()]
            if any(u["email"] == body["email"] for u in self.users.values()):
                return 409, {"detail": "email already exists"}
            if not set(body["roles"]) <= set(self.roles) | {"ADMIN"}:
                return 400, {"detail": "unknown role"}
            user = {"id": str(uuid.uuid4()), **body}
            self.users[user["id"]] = user
            return 201, {k: v for k, v in user.items() if k != "password"}
        if m := re.fullmatch(r"/api/users/([\w-]+)/roles", path):
            self.users[m[1]]["roles"] = body["roles"]
            return 200, {}
        if m := re.fullmatch(r"/api/objects/(\w+)/records", path):
            return self.handle_records(method, m[1], query, body)
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
        return 404, {"detail": f"no route {method} {raw_path}"}

    def handle_records(self, method, obj, query, body):
        if obj not in self.objects:
            return 404, {"detail": f"unknown object {obj}"}
        stored = self.records.setdefault(obj, [])
        if method == "GET":
            filters = {k: v for k, v in query.items() if k not in ("size", "page", "sort")}
            found = [r for r in stored if all(str(r["attributes"].get(k)) == v for k, v in filters.items())]
            return 200, {"content": found, "totalElements": len(found)}
        fields = {f["name"]: f for f in self.objects[obj]["fields"]}
        attributes = body["attributes"]
        if unknown := set(attributes) - set(fields):
            return 400, {"detail": f"unknown fields {sorted(unknown)}"}
        for rel in self.relationships.values():
            if rel["source"] == obj and rel["fieldName"] in attributes:
                if not any(r["id"] == attributes[rel["fieldName"]] for r in self.records.get(rel["target"], [])):
                    return 400, {"detail": f"{rel['fieldName']}: unknown {rel['target']} id"}
        record = {"id": str(uuid.uuid4()), "attributes": attributes}
        stored.append(record)
        return 201, record


class ApplyDemoTest(unittest.TestCase):
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
        self.args = ["--url", f"http://127.0.0.1:{self.server.server_port}", "--email", "a@b.c", "--password", "p", "--demo"]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def run_main(self, password="clave-de-prueba-demo", args=None):
        env = {k: v for k, v in os.environ.items() if k != "WASICHAI_DEMO_PASSWORD"}
        if password is not None:
            env["WASICHAI_DEMO_PASSWORD"] = password
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = apply.main(args or self.args)
        return code, out.getvalue(), err.getvalue()

    def test_first_run_creates_users_and_records_with_resolved_relations(self):
        code, _, err = self.run_main()
        self.assertEqual(0, code, err)
        self.assertEqual({u["email"] for u in DATA["users"]}, {u["email"] for u in self.fake.users.values()})
        self.assertEqual({"clave-de-prueba-demo"}, {u["password"] for u in self.fake.users.values()})
        for obj in {r["object"] for r in DATA["records"]}:
            self.assertEqual(len(records(obj)), len(self.fake.records.get(obj, [])), obj)
        # los perfiles docentes apuntan a la cuenta de Wasichai de su docente
        user_ids = {u["email"]: u["id"] for u in self.fake.users.values()}
        profiles = {r["attributes"]["nombre_publico"]: r["attributes"]["usuario_id"] for r in self.fake.records["perfil_docente"]}
        self.assertEqual(user_ids["docente@demo.wasichai-learning.test"], profiles["Diego Docente (demo)"])

    def test_second_run_writes_nothing(self):
        self.assertEqual(0, self.run_main()[0])
        writes = len(self.fake.writes)
        code, out, _ = self.run_main()
        self.assertEqual(0, code)
        # la única escritura de la segunda ejecución es el inicio de sesión
        self.assertEqual(["POST /api/auth/login"], self.fake.writes[writes:])
        self.assertIn("Sin cambios", out)

    def test_existing_user_gets_the_declared_roles_without_a_new_account(self):
        self.assertEqual(0, self.run_main()[0])
        multi = next(u for u in self.fake.users.values() if u["email"] == "docente.revisor@demo.wasichai-learning.test")
        multi["roles"] = ["DOCENTE"]
        writes = len(self.fake.writes)
        self.assertEqual(0, self.run_main()[0])
        self.assertEqual(["POST /api/auth/login", f"PUT /api/users/{multi['id']}/roles"], self.fake.writes[writes:])
        self.assertEqual(["DOCENTE", "REVISOR"], multi["roles"])

    def test_missing_demo_password_is_a_clear_error_before_any_call(self):
        code, _, err = self.run_main(password=None)
        self.assertEqual(2, code)
        self.assertIn("WASICHAI_DEMO_PASSWORD", err)
        self.assertEqual([], self.fake.writes)
        self.assertEqual(2, self.run_main(password="  ")[0])

    def test_without_demo_flag_no_demo_data_is_written(self):
        self.assertEqual(0, self.run_main(password=None, args=self.args[:-1])[0])
        self.assertEqual({}, self.fake.users)
        self.assertEqual({}, self.fake.records)

    def test_duplicated_natural_key_on_the_server_stops_the_run(self):
        self.assertEqual(0, self.run_main()[0])
        stored = self.fake.records["categoria"]
        stored.append(copy.deepcopy(stored[0]) | {"id": str(uuid.uuid4())})
        code, _, err = self.run_main()
        self.assertEqual(1, code)
        self.assertIn("misma clave natural", err)


if __name__ == "__main__":
    unittest.main()
