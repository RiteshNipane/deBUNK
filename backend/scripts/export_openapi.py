"""Writes openapi.json (the API contract). Run from backend/:  python scripts/export_openapi.py"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402

spec = create_app(Settings()).openapi()
out = Path(__file__).resolve().parents[1] / "openapi.json"
out.write_text(json.dumps(spec, indent=2) + "\n")
print("wrote", out)
