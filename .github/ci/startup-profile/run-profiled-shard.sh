#!/usr/bin/env bash
set -euo pipefail
# Diagnostic branch only, after the ordinary CI compile and shard-list checks.
# Keep the same full shard.txt as the paired uninstrumented run.
test -s shard.txt
install -d -m 700 target/ci-profile
jfr configure --input "$JAVA_HOME/lib/jfr/profile.jfc" --output target/ci-profile/safe-profile.jfc \
  'jdk.InitialEnvironmentVariable#enabled=false' 'jdk.InitialSystemProperty#enabled=false' \
  'jdk.JVMInformation#enabled=false' 
./mvnw surefire:test -B   -Dsurefire.includesFile="$PWD/shard.txt"   -Dsurefire.failIfNoTests=true   -Dsurefire.timeout=1200   -Dquarkus.builder.metrics.enabled=true   "-Dfloci.ci.profile.args=-XX:FlightRecorderOptions=stackdepth=128 -XX:StartFlightRecording=name=full-shard,settings=$PWD/target/ci-profile/safe-profile.jfc,dumponexit=true,maxsize=256m,filename=$PWD/target/ci-profile/surefire.jfr"
