#!/usr/bin/env python3
"""Exercise local Compose via Next; retain only a synthetic login for persistence checks."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--reuse", action="store_true", help="Verify the previous synthetic account/shop survived recreation")
args = parser.parse_args()
state_path = Path(__file__).resolve().parents[1] / "target/container-demo-smoke.json"
if args.reuse:
    state = json.loads(state_path.read_text())
else:
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state = {"email": "docker-" + uuid.uuid4().hex + "@example.test", "password": secrets.token_urlsafe(24)}
    with os.fdopen(os.open(state_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as target:
        json.dump(state, target)

client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def call(method, path, status, body=None, headers=None):
    request = urllib.request.Request("http://127.0.0.1:3000/api/v1" + path,
        data=None if body is None else json.dumps(body).encode(), method=method,
        headers={"Accept": "application/json", **({"Content-Type": "application/json"} if body is not None else {}), **(headers or {})})
    try:
        response = client.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        if response.status != status:
            raise RuntimeError(f"{method} {path}: expected {status}, received {response.status}")
        content = response.read()
        return json.loads(content) if content else None

def csrf():
    value = call("GET", "/auth/csrf", 200)
    assert value["headerName"] == "X-CSRF-TOKEN" and value["token"]
    return {value["headerName"]: value["token"]}

if not args.reuse:
    call("POST", "/auth/registrations", 201,
         {**state, "displayName": "Docker Demo"}, csrf())
account = call("POST", "/auth/sessions", 200, {"email": state["email"], "password": state["password"]}, csrf())
assert call("GET", "/me", 200)["id"] == account["id"]
if args.reuse:
    assert account["id"] == state["accountId"]
    shop = call("GET", "/me/shop", 200)
    assert shop["id"] == state["shopId"]
else:
    shop = call("POST", "/shops", 201, {
        "name": "Docker Demo Farm", "description": "Synthetic local demo", "contactPhone": "0900000000",
        "pickupAddress": {"countryCode": "VN", "provinceCode": "TEST-01", "provinceName": "Demo Province",
                          "wardCode": "TEST-001", "wardName": "Demo Ward", "streetAddress": "12 Demo Street"}},
        {**csrf(), "Idempotency-Key": str(uuid.uuid4())})
    state.update(accountId=account["id"], shopId=shop["id"])
    state_path.write_text(json.dumps(state))
assert shop["status"] == "DRAFT" and shop["ownerAccountId"] == account["id"]
assert call("GET", "/me/shop", 200)["id"] == shop["id"]
call("GET", "/admin/shops?page=0&pageSize=20", 403)
assert call("GET", "/products", 200)["items"] == []
assert call("GET", "/me/cart", 200)["items"] == []
product_id = str(uuid.uuid4())
assert call("GET", f"/products/{product_id}/reviews", 200)["items"] == []
assert call("GET", f"/products/{product_id}/review-summary", 200) == {"count": 0, "averageRating": None}
call("GET", "/seller/reviews", 403)  # DRAFT shop is not eligible, even with an authenticated owner.
call("GET", "/me/payment-attempts/" + str(uuid.uuid4()), 404)
call("DELETE", "/auth/sessions/current", 204, headers=csrf())
call("GET", "/me", 401)
print("PASS: real Next/auth/merchant/commerce/payment/feedback routes; " + ("account/shop persisted." if args.reuse else "synthetic account/shop created."))
