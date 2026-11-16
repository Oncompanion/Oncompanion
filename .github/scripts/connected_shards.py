#!/usr/bin/env python3
# Co-authored-by: Claude Code <noreply@anthropic.com>
"""Shares the connected tests between the shards by their recorded time, not by their name.

  plan <shard, from 1> <shards> <times file>
      Reads on stdin the test list of `am instrument -r -e log true` (the tests reported without
      running them) and prints this shard's tests, one "class#method" per line, for
      AndroidJUnitRunner's testFile. Every shard computes the same plan from the same list.

  times <JUnit report>...
      Prints the times file from the JUnit reports of the shards (the test-results-* artifacts of
      one or more CI runs): one "seconds class#method" line per test, sorted by name.

The plan gives each test, the longest first, to the shard with the least time so far. A test
missing from the times file (a new one) counts as the average test, so the plan only needs a new
times file when the tests' times have changed a lot.
"""
import sys
from xml.etree import ElementTree

STATUS = "INSTRUMENTATION_STATUS: "
STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
START = 1  # AndroidJUnitRunner's code for a test that starts (InstrumentationResultPrinter)


def listed_tests(lines):
    """The tests of `am instrument -r -e log true`, as "class#method", in the order listed."""
    tests, status = [], {}
    for raw in lines:
        line = raw.rstrip("\r\n")
        if line.startswith(STATUS):
            key, _, value = line[len(STATUS) :].partition("=")
            status[key] = value
        elif line.startswith(STATUS_CODE):
            if int(line[len(STATUS_CODE) :]) == START and status.get("test"):
                tests.append(f"{status.get('class', '')}#{status['test']}")
            status = {}
    return tests


def read_times(path):
    times = {}
    with open(path, encoding="utf-8") as file:
        for line in file:
            seconds, _, test = line.strip().partition(" ")
            if test:
                times[test] = float(seconds)
    return times


def plan(tests, times, shards):
    """The tests of each shard: the longest test first, each to the shard with the least time."""
    average = sum(times.values()) / len(times) if times else 1.0
    loads, shares = [0.0] * shards, [[] for _ in range(shards)]
    # Sorted by time then name, so every shard computes the same plan whatever the listed order
    for test in sorted(set(tests), key=lambda test: (-times.get(test, average), test)):
        shard = loads.index(min(loads))
        loads[shard] += times.get(test, average)
        shares[shard].append(test)
    return shares, loads


def main(args):
    if len(args) == 4 and args[0] == "plan":
        shard, shards = int(args[1]), int(args[2])
        tests = listed_tests(sys.stdin)
        if not tests:
            sys.exit("No test listed: the test APK couldn't be read")
        shares, loads = plan(tests, read_times(args[3]), shards)
        print(
            f"Shard {shard}/{shards}: {len(shares[shard - 1])} of {len(set(tests))} tests, "
            f"about {loads[shard - 1]:.0f} s (shards: {', '.join(f'{load:.0f}' for load in loads)})",
            file=sys.stderr,
        )
        print("\n".join(shares[shard - 1]))
    elif len(args) > 1 and args[0] == "times":
        runs = {}
        for path in args[1:]:
            for case in ElementTree.parse(path).iter("testcase"):
                test = f"{case.get('classname')}#{case.get('name')}"
                runs.setdefault(test, []).append(float(case.get("time", 0)))
        # A test in the reports of several runs gets its average time, steadier than one run's
        for test in sorted(runs):
            print(f"{sum(runs[test]) / len(runs[test]):.1f} {test}")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
