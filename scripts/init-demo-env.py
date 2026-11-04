#!/usr/bin/env python3
"""Create missing demo credentials; preserve every configured value and native .env."""
import os
from pathlib import Path
import secrets

path = Path(__file__).resolve().parents[1] / ".env.docker"
old = path.read_text() if path.exists() else ""
keys = {line.split("=", 1)[0].strip() for line in old.splitlines() if "=" in line and not line.lstrip().startswith("#")}
values = {"DEMO_DB_PASSWORD": lambda: secrets.token_urlsafe(32), "DEMO_INTERNAL_TOKEN": lambda: secrets.token_urlsafe(48),
          "EMAIL_TOKEN_ENCRYPTION_KEY": lambda: secrets.token_urlsafe(32)}
missing = [key + "=" + generate() for key, generate in values.items() if key not in keys]
if missing:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(descriptor, "w") as target:
        target.write(("\n" if old and not old.endswith("\n") else "") + "\n".join(missing) + "\n")
    os.chmod(path, 0o600)
    print("Added missing demo declarations; existing values and native .env unchanged.")
else:
    print("Demo declarations already configured; values unchanged.")
