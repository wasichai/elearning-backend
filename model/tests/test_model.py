"""El modelo y los roles reales: válidos, y con la separación de deberes que exige la especificación (sección 7)."""

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import apply  # noqa: E402

MODEL = apply.load(HERE / "model.json")
ROLES = apply.load(HERE / "roles.json")


def holds(role: str, obj: str, action: str) -> bool:
    found = next(r for r in ROLES["roles"] if r["name"] == role)
    return any(g["object"] == obj and action in g["actions"] for g in found["permissions"])


class RealModelTest(unittest.TestCase):
    def test_model_and_roles_are_valid(self):
        apply.validate(MODEL, ROLES)

    def test_the_four_lms_roles_exist(self):
        self.assertEqual({"ESTUDIANTE", "DOCENTE", "CREADOR", "REVISOR"}, {r["name"] for r in ROLES["roles"]})

    def test_nobody_but_admin_publishes_or_retires(self):
        for role in ROLES["roles"]:
            for action in ("PUBLICAR", "RETIRAR"):
                self.assertFalse(holds(role["name"], "version_curso", action), f"{role['name']} no debe {action}")

    def test_creator_does_not_approve_and_reviewer_does_not_author(self):
        self.assertFalse(holds("CREADOR", "version_curso", "APROBAR"))
        # el revisor necesita UPDATE para aprobar por la API de registros; qué puede cambiar lo decide
        # EditorialWorkflowGuard (solo la transición que su acción permite, nunca el contenido de otro)
        for action in ("CREATE", "DELETE", "ENVIAR_A_REVISION"):
            self.assertFalse(holds("REVISOR", "version_curso", action))

    def test_teachers_do_not_edit_offerings_until_their_scope_exists(self):
        # F1: sin alcance por asignación, UPDATE sobre edicion_curso sería sobre cualquier edición
        self.assertFalse(holds("DOCENTE", "edicion_curso", "UPDATE"))

    def test_students_read_content_only_through_their_enrollments(self):
        # leen versiones, unidades y lecciones, pero ContentReadScope las limita a sus matrículas activas: sin VER_TODAS
        for obj in ("version_curso", "unidad", "leccion", "edicion_curso"):
            self.assertTrue(holds("ESTUDIANTE", obj, "READ"), obj)
            self.assertFalse(holds("ESTUDIANTE", obj, "VER_TODAS"), obj)
            for staff in ("DOCENTE", "CREADOR", "REVISOR"):
                self.assertTrue(holds(staff, obj, "VER_TODAS"), f"{staff} {obj}")
        for obj in ("perfil_docente", "docente_edicion"):
            self.assertFalse(holds("ESTUDIANTE", obj, "READ"), obj)

    def test_teacher_profiles_are_private_by_default(self):
        fields = {f["name"]: f for o in MODEL["objects"] if o["name"] == "perfil_docente" for f in o["fields"]}
        self.assertEqual("false", fields["perfil_publico"]["defaultValue"])

    def test_staff_do_not_read_the_teachers_user_id(self):
        # #65: el usuario_id une el perfil público con una cuenta; solo ADMIN lo necesita
        for role in ROLES["roles"]:
            if holds(role["name"], "perfil_docente", "READ"):
                hidden = [f for f in role.get("fieldPermissions", []) if f["object"] == "perfil_docente" and f["field"] == "usuario_id"]
                self.assertEqual([False], [f["read"] for f in hidden], role["name"])

    def test_students_only_read_except_enrolling(self):
        student = next(r for r in ROLES["roles"] if r["name"] == "ESTUDIANTE")
        for grant in student["permissions"]:
            expected = {"READ", "CREATE"} if grant["object"] == "matricula" else {"READ"}
            self.assertEqual(expected, set(grant["actions"]), grant["object"])

    def test_enrollments_are_written_only_by_the_command(self):
        matricula = next(o for o in MODEL["objects"] if o["name"] == "matricula")
        self.assertTrue(matricula["apiOnly"])
        self.assertFalse(holds("ESTUDIANTE", "matricula", "VER_TODAS"))
        for role in ("ESTUDIANTE", "DOCENTE", "CREADOR", "REVISOR"):
            self.assertFalse(holds(role, "matricula", "UPDATE") or holds(role, "matricula", "DELETE"), role)

    def test_versions_start_as_draft_and_illustrative(self):
        version = next(o for o in MODEL["objects"] if o["name"] == "version_curso")
        fields = {f["name"]: f for f in version["fields"]}
        self.assertEqual("BORRADOR", fields["estado"]["defaultValue"])
        self.assertEqual("true", fields["contenido_ilustrativo"]["defaultValue"])
        self.assertNotIn("defaultValue", fields["creditos_aprobados"], "los créditos aprobados los pone una persona")

    def test_curriculum_is_separate_from_offering_and_schedule(self):
        # el temario (curso/versión) no tiene fechas; la edición sí, con su zona horaria
        course = {f["name"] for o in MODEL["objects"] if o["name"] in ("curso", "version_curso") for f in o["fields"]}
        self.assertFalse({"fecha_inicio", "fecha_fin", "zona_horaria"} & course)
        offering = {f["name"] for o in MODEL["objects"] if o["name"] == "edicion_curso" for f in o["fields"]}
        self.assertTrue({"fecha_inicio", "fecha_fin", "zona_horaria"} <= offering)


if __name__ == "__main__":
    unittest.main()
