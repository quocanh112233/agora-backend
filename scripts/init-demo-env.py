#!/usr/bin/env python3
"""Create separate demo credentials once; never replace local/native configuration."""
import os
from pathlib import Path
import secrets

path = Path(__file__).resolve().parents[1] / ".env.docker"
try:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    print(".env.docker exists; kept unchanged.")
else:
    with os.fdopen(descriptor, "w") as target:
        target.write("DEMO_DB_PASSWORD=" + secrets.token_urlsafe(32) + "\n")
        target.write("DEMO_INTERNAL_TOKEN=" + secrets.token_urlsafe(48) + "\n")
    print("Created ignored .env.docker (mode600); no existing .env changed.")
