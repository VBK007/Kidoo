#!/usr/bin/env python3
"""Project Kidoo playback logs onto a fixed set of safe fields."""

import argparse
import re
import subprocess
import sys

EVENTS = {
    "mirror playback request": "playback",
    "mirror upstream request": "upstream",
    "mirror playback startup": "startup",
    "mirror token decision": "token",
    "mirror manifest decision": "manifest",
    "mirror executor admission": "admission",
    "mirror master failover": "failover",
    "mirror master lookup": "cache",
}

FIELDS = {
    "playbackAttemptId": r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}",
    "status": r"[1-5][0-9]{2}",
    "upstreamStatus": r"[1-5][0-9]{2}",
    "durationMs": r"[0-9]{1,9}",
    "inFlight": r"[0-9]{1,5}",
    "operation": r"master|playlist|heartbeat|variant|subtitle|alternate|other",
    "requestType": r"heartbeat|title_detail|playlist|session_verify|discovery|browse|manifest|other",
    "cache": r"hit|miss|joined|bypass",
    "attemptSource": r"client|server",
    "role": r"owner|joiner",
    "tokenOutcome": (
        r"VERIFIED|REQUIRED|MALFORMED|UNKNOWN|ACCESS_DENIED|verification_request_failed"
        r"|cache_hit|cache_miss|token_extracted"
    ),
    "tokenPresent": r"true|false",
    "sessionGeneration": r"[0-9]{1,19}",
    "startupOperation": r"authorization|heartbeat|title_open",
    "startupOutcome": (
        r"verified|blocked|not_dispatched_authorization|completed|blocked_http|blocked_transport"
        r"|blocked_local|optional_http_failure|optional_transport_failure"
    ),
    "manifestDecision": (
        r"MASTER|MEDIA|MALFORMED|AMBIGUOUS"
        r"|restriction_(?:tag_attribute|comment_or_tag|uri_line_candidate|other_text)"
    ),
    "dispatched": r"true|false",
    "admissionOutcome": (
        r"cooldown|admission_busy|admission_failed|transport_error|upstream_response"
        r"|admitted|queue_expired|queue_full"
    ),
    "queueWaitMs": r"[0-9]{1,12}",
    "outboundWaitMs": r"[0-9]{1,12}",
    "cooldownRemainingMs": r"[0-9]{1,19}",
    "cooldownRemainingSeconds": r"[0-9]{1,19}",
}
FIELD_PATTERNS = {
    name: re.compile(r"(?:^|\s)" + name + r'=(?:"([^"\s]+)"|([^"\s]+))(?=\s|$)')
    for name in FIELDS
}


def project(raw):
    line = raw.lower()
    event = next((event for message, event in EVENTS.items() if message in line), None)
    if event is None:
        if re.search(r"multiple[- ]connections|too many connections", line):
            event = "possible-multiple-connections"
        elif "coalesc" in line and FIELD_PATTERNS["role"].search(raw):
            event = "coalescer"
        else:
            return None

    timestamp = re.match(r"\d{2}:\d{2}:\d{2}\.\d{3}(?=\s)", raw)
    result = [timestamp.group(0) if timestamp else "-", "event=" + event]
    for name, pattern in FIELD_PATTERNS.items():
        field = pattern.search(raw)
        if field:
            value = field.group(1) or field.group(2)
            if re.fullmatch(FIELDS[name], value):
                label = "localStatus" if name == "status" and event == "playback" else name
                result.append(label + "=" + value)
    return " ".join(result)


def display(lines):
    for raw in lines:
        projected = project(raw)
        if projected:
            print(projected, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--stdin", action="store_true", help="read pasted or synthetic logs")
    source.add_argument("--follow", action="store_true", help="follow live container output")
    parser.add_argument("--since", default="10m")
    options = parser.parse_args()
    try:
        if options.stdin:
            display(sys.stdin)
            return 0
        commands = ["docker", "logs", "--since", options.since]
        if options.follow:
            commands.append("--follow")
        commands.append("homeserver-kidoo-1")
        with subprocess.Popen(commands, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, errors="replace") as logger:
            try:
                display(logger.stdout)
            except KeyboardInterrupt:
                logger.terminate()
                return 130
            return logger.wait()
    except FileNotFoundError:
        print("Docker executable unavailable", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
