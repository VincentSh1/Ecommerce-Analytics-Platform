#!/usr/bin/env python3
"""A bounded correctness smoke test, not a load generator. Uses Python's standard library."""
from contextlib import closing
import datetime as dt
import http.client
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ingestion = os.environ.get("INGESTION_URL", "http://127.0.0.1:8081")
analytics = os.environ.get("ANALYTICS_URL", "http://127.0.0.1:8082")


def request(url, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=12) as response:
        return response.status, json.load(response)


def wait_ready(base):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            status, body = request(base + "/actuator/health/readiness")
            if status == 200 and body == {"status": "UP"}:
                return
        except (urllib.error.URLError, OSError, http.client.HTTPException):
            pass
        time.sleep(2)
    raise RuntimeError("Readiness deadline exceeded: " + base)


def timestamp(value):
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def check_http_boundaries():
    for base in (ingestion, analytics):
        assert request(base + "/actuator/health/liveness") == (200, {"status": "UP"})
        try:
            request(base + "/actuator/env")
        except urllib.error.HTTPError as exc:
            assert exc.code == 404
            assert json.load(exc)["error"]["code"] == "NOT_FOUND"
        else:
            raise AssertionError("Internal environment endpoint is exposed")
    url = urllib.parse.urlsplit(ingestion)
    connection_type = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
    with closing(connection_type(url.hostname, url.port, timeout=12)) as connection:
        connection.request("POST", "/api/v1/events", body=iter([b" " * 1500] * 3),
                           headers={"Content-Type": "application/json"}, encode_chunked=True)
        response = connection.getresponse()
        assert response.status == 413
        assert json.loads(response.read())["error"]["code"] == "PAYLOAD_TOO_LARGE"


def main():
    wait_ready(ingestion)
    wait_ready(analytics)
    check_http_boundaries()
    end = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0)
    start = end - dt.timedelta(minutes=1)
    query = analytics + "/api/v1/analytics/summary?" + urllib.parse.urlencode({"from": timestamp(start), "to": timestamp(end)})
    _, baseline = request(query)
    order = str(uuid.uuid4())

    def event(kind, amount):
        return {"schemaVersion": 1, "eventId": str(uuid.uuid4()), "eventType": kind,
                "orderId": order, "occurredAt": timestamp(start), "amountMinor": amount,
                "currency": "USD", "productCategory": "BOOKS", "region": "NA", "quantity": 1}

    first = event("PAYMENT_COMPLETED", 12500)
    second = event("PAYMENT_COMPLETED", 7500)
    second["orderId"] = str(uuid.uuid4())
    refund = event("REFUND_ISSUED", 12500)
    for payload in (first, first, second, refund):
        status, body = request(ingestion + "/api/v1/events", payload)
        assert status == 202 and body["status"] == "ACCEPTED" and body["eventId"] == payload["eventId"], body
        print("202 ACCEPTED", payload["eventId"], payload["eventType"])
    expected = {key: int(baseline["data"][key]) + delta for key, delta in
                {"grossMinor": 20000, "refundMinor": 12500, "netMinor": 7500,
                 "completedCount": 2, "refundCount": 1, "eventCount": 3}.items()}
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        _, result = request(query)
        if all(int(result["data"][key]) == value for key, value in expected.items()):
            time.sleep(2)
            _, confirmed = request(query)
            assert all(int(confirmed["data"][key]) == value for key, value in expected.items()), confirmed
            print(json.dumps(confirmed, indent=2))
            print("PASS: four acknowledged requests produced three unique financial effects.")
            return
        time.sleep(1)
    raise AssertionError({"expected": expected, "last": result})


if __name__ == "__main__":
    main()
