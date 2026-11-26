#!/usr/bin/env bash
# Full demonstration with no API key, using real Kafka, PostgreSQL, MongoDB and Chromium.
set -uo pipefail
cd "$(dirname "$0")"

export AI_PROVIDER=scripted AI_SCRIPT="$PWD/demo-shopflow/scripted-model/script.yaml"
mkdir -p .demo-logs

./gradlew -q :quartet-generator:installDist :demo-shopflow:installDist || exit 1
AGENT=quartet-generator/build/install/quartet-qe/bin/quartet-qe

echo "== Starting ShopFlow with real Kafka, PostgreSQL and MongoDB"
demo-shopflow/build/install/demo-shopflow/bin/demo-shopflow > .demo-logs/shopflow.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null' EXIT
for _ in $(seq 1 180); do curl -sf http://localhost:18080/metrics >/dev/null && break; sleep 1; done
curl -sf http://localhost:18080/metrics >/dev/null || { echo "ShopFlow did not start. See .demo-logs/shopflow.log"; exit 1; }

echo; echo "== 1. A vague story gets questions before any test is written"
$AGENT check-story --story stories/SHOP-200-needs-work.md

echo; echo "== 2. Context, story, tests, review"
rm -rf e2e-tests/recordings/CheckoutReservesStockTest.* e2e-tests/recordings/BrittleOrderTest.*
$AGENT run --story stories/SHOP-142.md --prove
GENERATE=$?

echo; echo "== 3. Second run replays verified recordings with no AI"
env -u AI_SCRIPT AI_PROVIDER=none QUARTET_MODE=replay ./gradlew :e2e-tests:test --rerun --tests 'generated.shop142.*' --console=plain -i 2>/dev/null | grep -E '\[quartet\] Recordings|BUILD' 

echo; echo "== 4. Repairing a brittle test without weakening it"
mkdir -p e2e-tests/src/test/java/generated/brittle
cp demo-shopflow/scripted-model/BrittleOrderTest.java.txt e2e-tests/src/test/java/generated/brittle/BrittleOrderTest.java
$AGENT repair --file e2e-tests/src/test/java/generated/brittle/BrittleOrderTest.java --selector 'generated.brittle.*'
REPAIR=$?

echo; echo "Review page: reports/SHOP-142/review.html"
[ "$GENERATE" -eq 0 ] && [ "$REPAIR" -eq 0 ]
