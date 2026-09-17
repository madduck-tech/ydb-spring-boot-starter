#!/usr/bin/env python3
"""Require a release tag on main and a successful CI run for that exact commit."""
import json
import os
import re
import subprocess


def output(*args):
    return subprocess.check_output(args, text=True).strip()


tag = os.environ["RELEASE_TAG"]
if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
    raise SystemExit("Expected an exact release tag such as v0.1.1")
if os.environ["GITHUB_REF"] != "refs/heads/main":
    raise SystemExit("Dispatch the publishing workflow from main")
if output("git", "cat-file", "-t", f"refs/tags/{tag}") != "tag":
    raise SystemExit("Releases require an annotated tag")
commit = output("git", "rev-parse", f"refs/tags/{tag}^{{commit}}")
subprocess.run(["git", "merge-base", "--is-ancestor", commit, "origin/main"], check=True)
runs = json.loads(output("gh", "run", "list", "--repo", os.environ["GITHUB_REPOSITORY"],
                         "--workflow", "ci.yml", "--commit", commit, "--event", "push",
                         "--limit", "50", "--json", "status,conclusion,headSha,headBranch,url"))
trusted = [run for run in runs if run["headSha"] == commit and run["headBranch"] in ("main", tag)]
if not trusted or trusted[0]["status"] != "completed" or trusted[0]["conclusion"] != "success":
    raise SystemExit("The latest push CI run for this main/tag commit must succeed before publication")
with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
    stream.write(f"commit={commit}\n")
print(f"Verified {tag} at {commit}; CI: {trusted[0]['url']}")
