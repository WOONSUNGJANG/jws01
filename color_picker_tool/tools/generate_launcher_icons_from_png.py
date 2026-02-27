from __future__ import annotations

import sys
from pathlib import Path


def main() -> int:
    try:
        from PIL import Image  # type: ignore
    except Exception:
        print("Pillow(PIL) 이 필요합니다. 먼저: python -m pip install pillow", file=sys.stderr)
        return 2

    project_root = Path(__file__).resolve().parents[1]
    src = project_root / "mico.png"
    res = project_root / "android" / "app" / "src" / "main" / "res"

    if not src.exists():
        print(f"소스 PNG를 찾을 수 없습니다: {src}", file=sys.stderr)
        return 1
    if not res.exists():
        print(f"res 폴더를 찾을 수 없습니다: {res}", file=sys.stderr)
        return 1

    targets = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    im = Image.open(src).convert("RGBA")

    for folder, size in targets.items():
        out_dir = res / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / "ic_launcher.png"

        # Android 런처 아이콘: 단순 리사이즈(원본에 흰 배경 포함)
        scaled = im.resize((size, size), Image.Resampling.LANCZOS)
        scaled.save(out_path, format="PNG", optimize=True)
        print(f"Wrote {out_path} ({size}x{size})")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

