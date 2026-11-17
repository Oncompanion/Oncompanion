#!/usr/bin/env bash
# Co-authored-by: Claude Code <noreply@anthropic.com>
#
# Runs one shard of the connected tests on the running emulator, without Gradle: waits for the
# "Build APKs" job of this run, installs its two APKs, runs this shard's test methods with
# AndroidJUnitRunner's testFile (shared out by connected_shards.py), then saves the JUnit report and the coverage.
#
# Usage: run-connected-shard.sh <shard, from 1> <number of shards>
# Needs GH_TOKEN, GITHUB_RUN_ID and GITHUB_REPOSITORY (set by GitHub Actions).
set -euo pipefail

shard=$1
shards=$2
app_id=com.github.se.oncompanion
runner=$app_id.test/$app_id.utils.OncompanionTestRunner
coverage_on_device=/data/data/$app_id/coverage.ec
tests_on_device=/data/local/tmp/shard-tests.txt

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

# For about half a minute after booting, the emulator is still settling (Play services updating):
# Android can relaunch the test's activity, which leaves a test that set its screen once with
# setContent on a blank screen. Whichever test a shard runs first would be the one hit, so no test
# starts before 60 s after boot. This script starts once the emulator has booted, and the wait for
# the APKs counts too
settle=$((60 - SECONDS))
if [ "$settle" -gt 0 ]; then
  echo "Letting the emulator settle for $settle s"
  sleep "$settle"
fi

# This shard's tests, shared out by their recorded time (connected_shards.py): the runner lists
# every test without running it, and each shard picks its share of the same list
adb shell am instrument -r -w -e log true "$runner" > test-list.txt
# adb exits with 0 even when the listing crashed: without the runner's final OK code, this shard
# would plan from a partial list, and the tests it misses would run in no shard
grep -q '^INSTRUMENTATION_CODE: -1' test-list.txt || { echo "Couldn't list the tests:"; cat test-list.txt; exit 1; }
python3 .github/scripts/connected_shards.py plan "$shard" "$shards" .github/connected-test-times.txt \
  < test-list.txt > shard-tests.txt
adb push shard-tests.txt "$tests_on_device"
adb shell chmod 644 "$tests_on_device"

mkdir -p test-results
adb shell am instrument -r -w -e testFile "$tests_on_device" \
  -e coverage true -e coverageFile "$coverage_on_device" \
  "$runner" |
  python3 .github/scripts/instrumentation_to_junit.py "test-results/TEST-shard-$shard.xml"

# The debug app can read its own files: copy the coverage out, unchanged
coverage=shard-coverage/shard-$shard/coverage.ec
mkdir -p "shard-coverage/shard-$shard"
adb exec-out run-as "$app_id" cat "$coverage_on_device" > "$coverage"
# exec-out mixes run-as's errors ("No such file", "not debuggable") into the file: check it starts
# with JaCoCo's header (block 0x01, then 0xC0C0), rather than only that it isn't empty
if [ "$(head -c 3 "$coverage" | od -An -tx1 | tr -d ' \n')" != "01c0c0" ]; then
  echo "No coverage written: $(head -c 200 "$coverage")"
  exit 1
fi
