#!/usr/bin/env python3
# Co-authored-by: Claude Code <noreply@anthropic.com>
"""Turns the raw output of `adb shell am instrument -r` into a JUnit XML report.

Reads the output on stdin as the tests run and echoes it, so the log shows progress. Each test's
time is measured between its start and end lines, as nothing else gives it without Gradle.

Exits with 1 when a test failed, or when the run didn't finish (e.g. the app crashed): fewer
results than tests announced, or no "INSTRUMENTATION_CODE: -1" (Activity.RESULT_OK) at the end.

Usage: ... | instrumentation_to_junit.py <report.xml>
"""
import sys
import time
from xml.etree import ElementTree

STATUS = "INSTRUMENTATION_STATUS: "
STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
RESULT = "INSTRUMENTATION_RESULT: "
CODE = "INSTRUMENTATION_CODE: "

# AndroidJUnitRunner's status codes (InstrumentationResultPrinter)
START, OK, ERROR, FAILURE, IGNORED, ASSUMPTION_FAILURE = 1, 0, -1, -2, -3, -4


def parse(lines):
    """The finished tests as (class, name, code, seconds, stack), the number of tests announced,
    and the run's final code (None if it never came)."""
    results, started, status = [], {}, {}
    key, announced, final_code = None, 0, None
    for raw in lines:
        sys.stdout.write(raw)
        sys.stdout.flush()
        line = raw.rstrip("\r\n")
        if line.startswith(STATUS):
            key, _, value = line[len(STATUS) :].partition("=")
            status[key] = value
        elif line.startswith(STATUS_CODE):
            code = int(line[len(STATUS_CODE) :])
            test = (status.get("class", ""), status.get("test", ""))
            announced = max(announced, int(status.get("numtests", "0") or 0))
            now = time.monotonic()
            if code == START:
                started[test] = now
            else:
                results.append((*test, code, now - started.pop(test, now), status.get("stack", "")))
            status, key = {}, None
        elif line.startswith(RESULT):
            key = None
        elif line.startswith(CODE):
            final_code = int(line[len(CODE) :])
        elif key is not None:
            # A value on several lines, e.g. a stack trace
            status[key] += "\n" + line
    return results, announced, final_code


def write_report(results, path):
    failures = sum(1 for r in results if r[2] == FAILURE)
    errors = sum(1 for r in results if r[2] == ERROR)
    skipped = sum(1 for r in results if r[2] in (IGNORED, ASSUMPTION_FAILURE))
    suite = ElementTree.Element(
        "testsuite",
        name="connected",
        tests=str(len(results)),
        failures=str(failures),
        errors=str(errors),
        skipped=str(skipped),
        time=f"{sum(r[3] for r in results):.3f}",
    )
    for cls, name, code, seconds, stack in results:
        case = ElementTree.SubElement(
            suite, "testcase", classname=cls, name=name, time=f"{seconds:.3f}"
        )
        if code in (FAILURE, ERROR):
            tag = "failure" if code == FAILURE else "error"
            ElementTree.SubElement(case, tag, message=stack.split("\n", 1)[0]).text = stack
        elif code in (IGNORED, ASSUMPTION_FAILURE):
            ElementTree.SubElement(case, "skipped")
    ElementTree.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
    return failures + errors


def main():
    results, announced, final_code = parse(sys.stdin)
    failed = write_report(results, sys.argv[1])
    print(f"{len(results)} of {announced} tests ran, {failed} failed")
    if failed:
        return 1
    if len(results) < announced or final_code != -1:
        print("The run didn't finish: the app may have crashed (see the logcat)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
