"""Create the single-node Garage layout, access key, and document bucket.

Idempotent. Local development only. The access key and admin token are the
compose defaults, not production credentials.
"""

import json
import os
import time
import urllib.error
import urllib.request

ADMIN = os.environ["GARAGE_ADMIN_URL"].rstrip("/")
TOKEN = os.environ["GARAGE_ADMIN_TOKEN"]
ACCESS_KEY = os.environ["GARAGE_ACCESS_KEY"]
SECRET_KEY = os.environ["GARAGE_SECRET_KEY"]
BUCKET = os.environ.get("GARAGE_BUCKET", "aegistrace-documents")


def call(path, body=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        ADMIN + path,
        data=data,
        method=method or ("POST" if body is not None else "GET"),
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{path} {exc.code} {detail}") from exc


def wait_until_up():
    last = "not started"
    for _ in range(60):
        try:
            status = call("/v2/GetClusterStatus")
            nodes = [node for node in status.get("nodes", []) if node.get("isUp")]
            if nodes:
                return nodes[0]
        except Exception as exc:
            last = str(exc)
        time.sleep(1)
    raise RuntimeError(f"Garage admin API did not become ready: {last}")


def main():
    node = wait_until_up()
    if node.get("role") is None:
        call(
            "/v2/UpdateClusterLayout",
            {"roles": [{"id": node["id"], "zone": "dc1", "capacity": 1073741824, "tags": ["dev"]}]},
        )
        layout = call("/v2/GetClusterLayout")
        call("/v2/ApplyClusterLayout", {"version": int(layout["version"]) + 1})
    try:
        call(f"/v2/GetKeyInfo?id={ACCESS_KEY}")
    except RuntimeError as exc:
        if " 404 " not in str(exc) and "NoSuchAccessKey" not in str(exc) and "NotFound" not in str(exc):
            raise
        call("/v2/ImportKey", {"name": "aegis", "accessKeyId": ACCESS_KEY, "secretAccessKey": SECRET_KEY})
    try:
        bucket = call("/v2/GetBucketInfo?globalAlias=" + BUCKET)
    except RuntimeError as exc:
        if "404" not in str(exc) and "NoSuchBucket" not in str(exc) and "NotFound" not in str(exc):
            raise
        bucket = call("/v2/CreateBucket", {"globalAlias": BUCKET})
    call(
        "/v2/AllowBucketKey",
        {
            "bucketId": bucket["id"],
            "accessKeyId": ACCESS_KEY,
            "permissions": {"read": True, "write": True, "owner": True},
        },
    )
    print("garage_ready", BUCKET)


if __name__ == "__main__":
    main()
