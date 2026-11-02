#!/usr/bin/env bash
set -euo pipefail
cd src/test/java
find . \( -name '*Test.java' -o -name 'Test*.java' \
          -o -name '*Tests.java' -o -name '*TestCase.java' \) \
  | sed 's|^\./||; s|\.java$||' | LC_ALL=C sort \
  > "$GITHUB_WORKSPACE/all-classes.txt"
cd "$GITHUB_WORKSPACE"
.github/ci/partition.sh all-classes.txt "$SHARD_TOTAL" "$1" > shard.txt
# An empty includesFile makes surefire fall back to its default includes
# and run the ENTIRE suite into this shard's timeout.
test -s shard.txt
echo "shard $1/$SHARD_TOTAL: $(wc -l < shard.txt) classes"
