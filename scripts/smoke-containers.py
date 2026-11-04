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
import re
import time
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument("--reuse", action="store_true", help="Verify the previous synthetic account/shop survived recreation")
parser.add_argument("--mailpit-url", default="http://127.0.0.1:8025", help="Loopback SMTP test inbox; only used for newly registered fixtures")
args = parser.parse_args()
inbox = urllib.parse.urlsplit(args.mailpit_url)
if inbox.scheme != "http" or inbox.hostname not in {"127.0.0.1", "localhost", "::1"} or inbox.username or inbox.password or inbox.query or inbox.fragment or inbox.path not in {"", "/"}:
    raise RuntimeError("Only a loopback Mailpit inbox is allowed for fixture verification")
state_path = Path(__file__).resolve().parents[1] / "target/account-types-container-smoke.json"
if args.reuse:
    state = json.loads(state_path.read_text())
else:
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state = {"email": "docker-seller-" + uuid.uuid4().hex + "@example.test", "password": secrets.token_urlsafe(24),
             "buyerEmail": "docker-buyer-" + uuid.uuid4().hex + "@example.test", "buyerPassword": secrets.token_urlsafe(24)}
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

def verify_fixture(email):
    for _ in range(60):
        with urllib.request.urlopen(args.mailpit_url.rstrip("/") + "/api/v1/messages", timeout=5) as response:
            messages = json.load(response)["messages"]
        for message in messages:
            if not any(recipient["Address"].lower() == email.lower() for recipient in message["To"]):
                continue
            with urllib.request.urlopen(args.mailpit_url.rstrip("/") + "/api/v1/message/" + urllib.parse.quote(message["ID"], safe=""), timeout=5) as response:
                text = json.load(response)["Text"]
            match = re.search(r"http://127\.0\.0\.1:3000/verify-email#token=([A-Za-z0-9_-]{43})", text)
            if match:
                call("POST", "/auth/email-verifications/confirm", 204, {"token": match.group(1)}, csrf())
                return
        time.sleep(0.5)
    raise RuntimeError("Synthetic verification email did not reach local Mailpit; no token printed")

if not args.reuse:
    # Check the local test inbox before generating registrations; never use provider mail for these fixtures.
    with urllib.request.urlopen(args.mailpit_url.rstrip("/") + "/api/v1/messages", timeout=5):
        pass
    call("POST", "/auth/seller-registrations", 201,
         {"email": state["email"], "password": state["password"], "displayName": "Docker Demo Seller"}, csrf())
    call("POST", "/auth/registrations", 201,
         {"email": state["buyerEmail"], "password": state["buyerPassword"], "displayName": "Docker Demo Buyer"}, csrf())
    call("GET", "/me", 401)
    verify_fixture(state["email"])
    verify_fixture(state["buyerEmail"])
account = call("POST", "/auth/sessions", 200, {"email": state["email"], "password": state["password"]}, csrf())
assert call("GET", "/me", 200)["id"] == account["id"]
assert account["accountType"] == "SELLER"
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
call("GET", "/me/cart", 403)
call("GET", "/me/payment-attempts/" + str(uuid.uuid4()), 403)
product_id = str(uuid.uuid4())
assert call("GET", f"/products/{product_id}/reviews", 200)["items"] == []
assert call("GET", f"/products/{product_id}/review-summary", 200) == {"count": 0, "averageRating": None}
call("GET", "/seller/reviews", 403)  # DRAFT shop is not eligible, even with an authenticated owner.
call("DELETE", "/auth/sessions/current", 204, headers=csrf())
buyer = call("POST", "/auth/sessions", 200, {"email": state["buyerEmail"], "password": state["buyerPassword"]}, csrf())
assert buyer["accountType"] == "BUYER" and buyer["id"] != account["id"]
if args.reuse:
    assert buyer["id"] == state["buyerId"]
else:
    state["buyerId"] = buyer["id"]
    state_path.write_text(json.dumps(state))
assert call("GET", "/me/cart", 200)["items"] == []
call("GET", "/me/shop", 403)
call("GET", "/seller/products", 403)
call("GET", "/me/payment-attempts/" + str(uuid.uuid4()), 404)
call("DELETE", "/auth/sessions/current", 204, headers=csrf())
call("GET", "/me", 401)
print("PASS: real Next/auth/merchant/commerce/payment/feedback routes; " + ("account/shop persisted." if args.reuse else "separate synthetic BUYER/SELLER accounts and shop created."))
