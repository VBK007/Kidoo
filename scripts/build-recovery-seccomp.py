#!/usr/bin/env python3
"""Narrow Playwright v1.58.0's Docker seccomp profile for Chromium on Linux amd64."""

import hashlib
import json
import platform
import sys

SOURCE_SHA256 = "cc3e61cabda6bbc1e53e54d27ba4d55a9d3be829b6dd1a596f4a7b31b1cc7849"


def main():
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        sys.exit("This Chromium profile is validated only for Linux amd64")
    source = sys.stdin.buffer.read()
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        sys.exit("Unexpected Playwright seccomp baseline digest")
    profile = json.loads(source)
    rules = profile["syscalls"]
    index = next(i for i, rule in enumerate(rules)
                 if rule.get("comment") == "Allow create user namespaces")
    # Permit Chromium's user, PID and network namespaces. Other namespace
    # flags remain filtered. Kernel capability checks still apply to chroot.
    rules[index:index + 1] = [
        {
            "comment": "Allow only Chromium user, PID and network namespace flags",
            "names": ["clone", "unshare"],
            "action": "SCMP_ACT_ALLOW",
            "args": [{"index": 0, "value": 0x7E020000 & ~0x70000000,
                      "valueTwo": 0, "op": "SCMP_CMP_MASKED_EQ"}],
        },
        {
            "comment": "Chromium chroots inside its user namespace; kernel capability check still applies",
            "names": ["chroot"],
            "action": "SCMP_ACT_ALLOW",
            "args": [],
        },
    ]
    json.dump(profile, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
