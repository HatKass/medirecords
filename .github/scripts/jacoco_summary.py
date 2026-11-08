"""Prints a Markdown coverage table from JaCoCo's CSV report (used for the CI job summary).

Usage: python jacoco_summary.py backend/target/site/jacoco/jacoco.csv
"""
import csv
import sys
from collections import defaultdict

COUNTERS = ("LINE", "BRANCH", "INSTRUCTION", "METHOD")


def percent(covered: int, missed: int) -> str:
    total = covered + missed
    return f"{100 * covered / total:.1f}%" if total else "n/a"


def main(path: str) -> None:
    totals = defaultdict(int)
    packages = defaultdict(lambda: defaultdict(int))
    with open(path, newline="", encoding="utf-8") as report:
        for row in csv.DictReader(report):
            package = row["PACKAGE"].removeprefix("com.medirecords.") or "(root)"
            for counter in COUNTERS:
                for kind in ("COVERED", "MISSED"):
                    value = int(row[f"{counter}_{kind}"])
                    totals[(counter, kind)] += value
                    packages[package][(counter, kind)] += value

    def cells(counts, bold=False):
        values = [percent(counts[(c, "COVERED")], counts[(c, "MISSED")]) for c in ("LINE", "BRANCH")]
        return " | ".join(f"**{v}**" if bold else v for v in values)

    print("## Backend coverage (JaCoCo)\n")
    print("| Package | Lines | Branches |")
    print("|---|---:|---:|")
    for package in sorted(packages):
        print(f"| {package} | {cells(packages[package])} |")
    print(f"| **Total** | {cells(totals, bold=True)} |")


if __name__ == "__main__":
    main(sys.argv[1])
