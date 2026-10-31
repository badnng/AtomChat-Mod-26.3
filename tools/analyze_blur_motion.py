"""Measure blur-motion PNG evidence (requires Pillow); preserve metrics beside report.

Snapshot center-vs-border drift helps identify a cached old panel in the world
source. Surface alpha and color trajectories expose a solid-to-blurred jump.
These metrics are diagnostic; animation geometry and a live world preclude a
universal pixel threshold. The Java oracle separately asserts blur availability
through opening/closing and verifies stable cache refresh actually occurred.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageStat


def rmse(first: Image.Image, second: Image.Image) -> float:
    delta = ImageChops.difference(first.convert("RGB"), second.convert("RGB"))
    return math.sqrt(sum(value * value for value in ImageStat.Stat(delta).rms) / 3)


def analyze(report: Path) -> dict:
    data = json.loads(report.read_text(encoding="utf-8"))
    frames = report.parent / "frames"
    rows = data.get("blurMotionFrames", [])
    if not rows:
        raise ValueError("Report has no blurMotionFrames")
    result = {"report": str(report.resolve()), "snapshotDrift": [], "motionSamples": [],
              "interpretation": "RGB RMSE uses 0..255 channels. Compare matching backend/size. "
              "Center drift exceeding border drift suggests UI contamination; inspect snapshot PNGs. "
              "Large adjacent color changes near progress=1 suggest solid-to-blur discontinuity. "
              "Metrics are diagnostic, not calibrated visual pass/fail thresholds."}
    reference = None
    previous = {}
    for row in rows:
        if name := row.get("snapshotArtifact"):
            with Image.open(frames / name) as image:
                image = image.convert("RGB").resize((160, 90))
                if reference is None:
                    reference = image.copy()
                center = (32, 18, 128, 72)
                center_drift = rmse(image.crop(center), reference.crop(center))
                border_drift = sum(rmse(image.crop(box), reference.crop(box)) for box in
                                   [(0, 0, 160, 12), (0, 78, 160, 90), (0, 12, 20, 78), (140, 12, 160, 78)]) / 4
                result["snapshotDrift"].append({"artifact": name, "phase": row["phase"],
                    "capturedFrames": row["capturedFrames"], "centerRmse": center_drift,
                    "borderRmse": border_drift, "centerExcessRmse": center_drift - border_drift})
        if name := row.get("artifact"):
            with Image.open(frames / (name + "-surface.png")) as surface:
                rgba = surface.convert("RGBA")
                width, height = rgba.size
                # A broad central patch avoids panel shadow bounds and summarizes
                # both alpha and RGB instead of treating nonempty pixels as proof.
                patch = rgba.crop((width * 3 // 8, height * 3 // 8, width * 5 // 8, height * 5 // 8))
                stats = ImageStat.Stat(patch)
                sample = {"artifact": name, "phase": row["phase"], "progress": row["panelProgress"],
                          "blurDrawn": row["blurDrawnThisFrame"], "surfaceCenterMeanRGBA": stats.mean,
                          "surfaceCenterStddevRGBA": stats.stddev}
            with Image.open(frames / (name + "-composited.png")) as compositor:
                image = compositor.convert("RGB").resize((160, 90))
                old = previous.get(row["phase"])
                if old is not None:
                    sample["adjacentCompositorRmse"] = rmse(image, old)
                previous[row["phase"]] = image.copy()
            result["motionSamples"].append(sample)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    result = analyze(args.report)
    destination = args.report.with_name("blur-image-metrics.json")
    destination.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(destination)
    print(f"Analyzed {len(result['motionSamples'])} paired frames and {len(result['snapshotDrift'])} blur sources")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
