#!/usr/bin/env python3
"""Read-only local Docker sampling; run alongside, not inside, the load generator."""
import datetime
import json
from pathlib import Path
import subprocess
import sys
import time

output = Path(sys.argv[1])
deadline = time.monotonic() + float(sys.argv[2])
containers = ['ecommerce-benchmark-' + service + '-1' for service in ('localstack','ingestion-service','analytics-service')]
with output.open('x') as file:
    while time.monotonic() < deadline:
        sample = subprocess.run(['docker','stats','--no-stream','--format','{{json .}}', *containers],
                                text=True, capture_output=True, timeout=15)
        record = dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      exitCode=sample.returncode, containers=[json.loads(line) for line in sample.stdout.splitlines()])
        file.write(json.dumps(record)+'\n')
        file.flush()
        time.sleep(5)
