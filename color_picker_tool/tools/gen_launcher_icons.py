from __future__ import annotations

import os
from pathlib import Path

from PIL import Image


def main() -> None:
    repo = Path(r"c:\ATX_PIC\color_picker_tool")
    src = repo / "mico.png"
    res = repo / r"android\app\src\main\res"

    if not src.exists():
        raise SystemExit(f"Source image not found: {src}")

    # Standard legacy launcher icon sizes (px)
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    img = Image.open(src).convert("RGBA")

    written: list[tuple[str, int, str]] = []
    for d, s in sizes.items():
        out_dir = res / d
        out_dir.mkdir(parents=True, exist_ok=True)

        out = img.resize((s, s), Image.LANCZOS)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            out_path = out_dir / name
            out.save(out_path, format="PNG", optimize=True)
            written.append((d, s, str(out_path)))

    print("OK:")
    for d, s, p in written:
        try:
            size_bytes = os.path.getsize(p)
        except OSError:
            size_bytes = -1
        print(f"- {d} {s}px -> {p} ({size_bytes} bytes)")


if __name__ == "__main__":
    main()

