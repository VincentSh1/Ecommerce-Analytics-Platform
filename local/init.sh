#!/bin/sh
set -eu
python3 /opt/ecommerce/init.py
touch /tmp/ecommerce-initialized
