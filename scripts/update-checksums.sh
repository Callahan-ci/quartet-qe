#!/usr/bin/env bash
# Refreshes gradle/verification-metadata.xml after a dependency or plugin changes. The build refuses any download whose
# checksum is not listed there, so a changed or tampered library cannot slip in. Review the diff before committing it.
set -euo pipefail
cd "$(dirname "$0")/.."
# --refresh-dependencies: files already in the local cache are otherwise not recorded, and a clean machine (CI) then rejects them.
./gradlew --write-verification-metadata sha256 --refresh-dependencies compileTestJava :quartet-core:test :quartet-runtime:test :quartet-generator:test \
  installDist :quartet-generator:mcpbBundles :quartet-generator:distZip --console=plain
git --no-pager diff --stat -- gradle/verification-metadata.xml
