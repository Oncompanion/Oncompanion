#!/usr/bin/env bash
# Co-authored-by: Claude Code <noreply@anthropic.com>
#
# Runs one shard of the connected tests on the running emulator, without Gradle: waits for the
# "Build APKs" job of this run, installs its two APKs, runs this shard's test methods with
# AndroidJUnitRunner's numShards/shardIndex, then saves the JUnit report and the coverage.
#
# Usage: run-connected-shard.sh <shard, from 1> <number of shards>
# Needs GH_TOKEN, GITHUB_RUN_ID and GITHUB_REPOSITORY (set by GitHub Actions).
set -euo pipefail

shard=$1
shards=$2
app_id=com.github.se.oncompanion
runner=$app_id.test/$app_id.utils.OncompanionTestRunner
coverage_on_device=/data/data/$app_id/coverage.ec

# The emulator booted while the APKs were being built: wait for them (at most 15 min). Every 30 s:
# each run's waits share GITHUB_TOKEN's 1,000 API calls per hour with every other run of the repo
build=""
for _ in $(seq 30); do
  if build=$(gh run view "$GITHUB_RUN_ID" -R "$GITHUB_REPOSITORY" --json jobs \
    --jq '.jobs[] | select(.name == "Build APKs") | "\(.status) \(.conclusion)"'); then
    case "$build" in
      "") echo "No job named \"Build APKs\" in this run: rename it here and in ci.yml together"
        exit 1 ;;
      "completed success") break ;;
      completed*) echo "The Build APKs job ended with \"${build#completed }\"" && exit 1 ;;
    esac
  else
    # A GitHub API error (e.g. the rate limit) is said and tried again, not taken for "not done"
    echo "Couldn't read the Build APKs job, trying again"
  fi
  sleep 30
done
[ "$build" = "completed success" ] || { echo "The Build APKs job didn't finish in time"; exit 1; }

# Three tries, so a passing API error doesn't fail the shard after its emulator booted
for attempt in 1 2 3; do
  gh run download "$GITHUB_RUN_ID" -R "$GITHUB_REPOSITORY" -n debug-apks -D apks && break
  [ "$attempt" = 3 ] && { echo "Couldn't download the APKs"; exit 1; }
  rm -rf apks
  sleep $((attempt * 15))
done

# No "isn't responding" dialogs: on the slow emulator they take the focus from the app under
# test. The whole device's logcat is recorded, to see what happened when a test fails
adb shell settings put global hide_error_dialogs 1
adb logcat -v time > emulator-logcat.txt &

# The same install as Gradle's: replace, and allow test-only packages; no permission granted.
# The artifact keeps the folders under app/build/outputs/apk
adb install -r -t apks/debug/app-debug.apk
adb install -r -t apks/androidTest/debug/app-debug-androidTest.apk

mkdir -p test-results
adb shell am instrument -r -w \
  -e numShards "$shards" -e shardIndex "$((shard - 1))" \
  -e coverage true -e coverageFile "$coverage_on_device" \
  "$runner" |
  python3 .github/scripts/instrumentation_to_junit.py "test-results/TEST-shard-$shard.xml"

# The debug app can read its own files: copy the coverage out, unchanged
mkdir -p "shard-coverage/shard-$shard"
adb exec-out run-as "$app_id" cat "$coverage_on_device" > "shard-coverage/shard-$shard/coverage.ec"
[ -s "shard-coverage/shard-$shard/coverage.ec" ] || { echo "No coverage written"; exit 1; }
