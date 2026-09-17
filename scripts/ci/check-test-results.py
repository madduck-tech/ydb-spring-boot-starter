#!/usr/bin/env python3
"""Fail CI if a module has no tests or any test failed or was skipped."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def main():
    root = Path(__file__).resolve().parents[2]
    modules = ("ydb-spring", "ydb-spring-boot", "ydb-spring-boot-starter")
    integration_suite = "io.github.madducktech.ydb.starter.StarterIntegrationTests"
    integration_ran = False
    total = 0
    problems = []
    for module in modules:
        count = 0
        reports = sorted((root / module / "build/test-results/test").glob("TEST-*.xml"))
        for report in reports:
            suite = ET.parse(report).getroot()
            cases = suite.findall("testcase")
            count += len(cases)
            for case in cases:
                if any(case.find(kind) is not None for kind in ("failure", "error", "skipped")):
                    problems.append(f"{module}: {suite.get('name')}.{case.get('name')} did not pass")
            if module == "ydb-spring-boot-starter" and suite.get("name") == integration_suite and cases:
                integration_ran = True
        if count == 0:
            problems.append(f"{module}: no test results found")
        total += count
    if not integration_ran:
        problems.append("YDB integration test report is missing or empty")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(f"All {total} tests passed, including the real YDB integration test; no skips.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
