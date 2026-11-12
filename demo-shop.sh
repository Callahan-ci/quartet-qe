#!/usr/bin/env bash
# Starts the ShopFlow sample system (shop, Kafka, PostgreSQL, MongoDB) and leaves it running.
# Everything runs inside one Java process on this machine. No Docker. Stop it with Ctrl+C.
set -uo pipefail
cd "$(dirname "$0")"
./gradlew -q :demo-shopflow:installDist || exit 1
echo "Starting ShopFlow. The first start downloads database binaries, so allow a few minutes."
echo "Ready when this prints: ShopFlow listening. Leave this window open and run your tests."
exec demo-shopflow/build/install/demo-shopflow/bin/demo-shopflow
