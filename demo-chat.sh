#!/usr/bin/env bash
# The chat path with no API key: the real MCP server, a stand-in chat AI, and the real ShopFlow system.
# Shows the plumbing a chat tool uses (story, tests, review, a test run answered step by step through the chat).
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p .demo-logs

./gradlew -q :quartet-generator:installDist :demo-shopflow:installDist || exit 1

echo "== Starting ShopFlow with real Kafka, PostgreSQL and MongoDB"
demo-shopflow/build/install/demo-shopflow/bin/demo-shopflow > .demo-logs/shopflow.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null' EXIT
for _ in $(seq 1 180); do curl -sf http://localhost:18080/metrics >/dev/null && break; sleep 1; done
curl -sf http://localhost:18080/metrics >/dev/null || { echo "ShopFlow did not start. See .demo-logs/shopflow.log"; exit 1; }

rm -rf e2e-tests/recordings/CheckoutReservesStockTest.* e2e-tests/src/test/java/generated/shop142 reports/SHOP-142

echo; echo "== A chat AI drives Quartet QE over MCP, with no API key"
java -cp "demo-shopflow/build/install/demo-shopflow/lib/*" demo.shopflow.ChatDriver \
  "$PWD" "$PWD/demo-shopflow/scripted-model/script.yaml" "$PWD/quartet-generator/build/install/quartet-qe/bin/quartet-qe"
CHAT=$?

echo; echo "== Second run replays what the chat recorded, with no AI at all"
env -u AI_PROVIDER -u QUARTET_HOST_DIR AI_PROVIDER=none QUARTET_MODE=replay ./gradlew :e2e-tests:test --rerun --tests 'generated.shop142.*' --console=plain -i 2>/dev/null | grep -E '\[quartet\] Recordings|BUILD'
echo; echo "Review page: reports/SHOP-142/review.html"
exit $CHAT
