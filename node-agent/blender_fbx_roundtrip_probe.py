"""Isolated Blender FBX roundtrip experiment for a synthetic 3D asset.

Run explicitly inside Blender 5.2+:
  blender --background --factory-startup --python blender_fbx_roundtrip_probe.py -- OUT_DIR
No user assets are read, imported or overwritten. No Roblox upload or Studio edit.
"""
from __future__ import annotations
import hashlib
import json
import sys
import time
from pathlib import Path

import bpy


def _mesh_stats() -> list[dict]:
    out = []
    for ob in sorted((o for o in bpy.data.objects if o.type == "MESH"), key=lambda v: v.name):
        out.append({
            "name": ob.name,
            "vertices": len(ob.data.vertices),
            "triangles": sum(max(0, len(p.vertices) - 2) for p in ob.data.polygons),
            "dimensions": [round(float(dim), 5) for dim in ob.dimensions],
            "materials": len(ob.data.materials),
        })
    return out


def _dimensions_signature(rows: list[dict]) -> list[list[float]]:
    return sorted([sorted(r["dimensions"]) for r in rows])


def _near(a: list[list[float]], b: list[list[float]], tolerance: float = 0.025) -> bool:
    return len(a) == len(b) and all(
        abs(x-y) <= tolerance * max(1.0, abs(x), abs(y))
        for side_a, side_b in zip(a, b)
        for x, y in zip(side_a, side_b)
    )


def run(out_dir: Path) -> dict:
    if not out_dir.is_dir():
        raise RuntimeError("Explicitly create an isolated destination directory")
    out_file = out_dir / "synthetic_canopy_roundtrip.fbx"
    report_file = out_dir / "synthetic_canopy_roundtrip_report.json"
    if out_file.exists() or report_file.exists():
        raise RuntimeError("Refusing to overwrite existing experiment outputs")

    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=12, radius=0.23, depth=2.0, location=(0, 0, 1.0)
    )
    trunk = bpy.context.object
    trunk.name = "SyntheticTrunk"
    bark = bpy.data.materials.new("SyntheticBark")
    bark.diffuse_color = (0.20, 0.095, 0.045, 1.0)
    trunk.data.materials.append(bark)

    bpy.ops.mesh.primitive_cone_add(
        vertices=12, radius1=0.9, radius2=0.15,
        depth=2.4, location=(0, 0, 3.1)
    )
    canopy = bpy.context.object
    canopy.name = "SyntheticCanopy"
    green = bpy.data.materials.new("SyntheticFoliage")
    green.diffuse_color = (0.05, 0.25, 0.09, 1.0)
    canopy.data.materials.append(green)
    for obj in (trunk, canopy):
        bpy.context.view_layer.objects.active = obj
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)

    before = _mesh_stats()
    bpy.ops.object.select_all(action="DESELECT")
    for obj in (trunk, canopy):
        obj.select_set(True)

    export_status = bpy.ops.export_scene.fbx(
        filepath=str(out_file),
        use_selection=True,
        path_mode="COPY",
        axis_forward="Z",
        axis_up="Y",
        apply_unit_scale=True,
        apply_scale_options="FBX_SCALE_UNITS",
        bake_anim=False,
        use_mesh_modifiers=True,
    )
    if "FINISHED" not in export_status or not out_file.exists():
        raise RuntimeError("FBX exporter did not complete")

    fbx_bytes = out_file.read_bytes()
    if len(fbx_bytes) < 100:
        raise RuntimeError("Generated FBX is unexpectedly short")

    bpy.ops.wm.read_factory_settings(use_empty=True)
    import_status = bpy.ops.import_scene.fbx(filepath=str(out_file), axis_forward="Z", axis_up="Y")
    if "FINISHED" not in import_status:
        raise RuntimeError("FBX importer did not complete")

    after = _mesh_stats()
    metrics_ok = (
        len(before) == len(after) == 2
        and sum(p["vertices"] for p in before) == sum(p["vertices"] for p in after)
        and sum(p["triangles"] for p in before) == sum(p["triangles"] for p in after)
        and _near(_dimensions_signature(before), _dimensions_signature(after))
    )
    report = {
        "schema": "jade.local_blender_fbx_probe.v1",
        "status": "PASS" if metrics_ok else "FAIL",
        "blender_version": bpy.app.version_string,
        "executed_at_ms": int(time.time() * 1000),
        "experiment": "synthetic FBX roundtrip inside Blender only",
        "fbx_bytes": len(fbx_bytes),
        "fbx_sha256": hashlib.sha256(fbx_bytes).hexdigest(),
        "geometry_before": before,
        "geometry_after": after,
        "validations": {
            "two_mesh_objects": len(after) == 2,
            "vertex_count_preserved": sum(p["vertices"] for p in before)
                == sum(p["vertices"] for p in after),
            "triangles_preserved": sum(p["triangles"] for p in before)
                == sum(p["triangles"] for p in after),
            "bounding_dimensions_preserved": _near(
                _dimensions_signature(before), _dimensions_signature(after)),
        },
        "limitations": [
            "Roblox Studio import was not executed",
            "Materials, textures and shader rendering were not fidelity-tested",
            "This tests a synthetic sample, not a user's existing asset"
        ]
    }
    report_file.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("JADE_FBX_PROBE " + json.dumps({
        "status": report["status"],
        "fbx_sha256": report["fbx_sha256"],
        "validations": report["validations"],
    }))
    return report


if __name__ == "__main__":
    args = sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else []
    if len(args) != 1:
        raise SystemExit("usage: blender --background --python SCRIPT -- OUT_DIR")
    result = run(Path(args[0]).resolve())
    if result["status"] != "PASS":
        raise SystemExit(2)
