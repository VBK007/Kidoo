# Load testing the stream

`StreamLoadTest.java` simulates N people watching the same film at once. One
file, no build step, no dependencies:

```bash
java scripts/StreamLoadTest.java \
    --base http://localhost:8080 \
    --user <username> --password <password> \
    --item <mediaItemId> \
    --viewers 100 --seconds 120 --mode realtime
```

Get a `mediaItemId` from `GET /api/media/items?size=5` or the app.

## Run it on the server, or on its LAN

Pointing this at the ngrok tunnel measures ngrok. The free tier caps concurrent
connections and bandwidth far below what 100 viewers ask for, so the run would
describe a throttle rather than this machine. Use `http://localhost:8080` on the
server itself, or its LAN address from another box on the same switch.

## Read this before the results

A hundred viewers of a 1.6 Mbps file is **160 Mbps** of upstream. The household
has about 40 Mbps up — `app.parties.max-members` is set to 4 for exactly this
reason. So over the internet, 100 concurrent viewers is not slow, it is
arithmetically impossible; roughly **25** is the ceiling at 536p, and **4** at
1080p.

On the LAN the wall moves to the disk, the NIC and Tomcat, and you will see a
much larger number. That number is real and worth knowing — it is what the house
itself can do — but it is not how many friends can watch from outside.

So this is not a pass/fail test. It answers: where does it bend, does it bend
gracefully, and what gives way first.

## Modes

- `--mode realtime` (default) — each viewer pulls at roughly the file's bitrate,
  which is what watching looks like. The line that matters is **kept up**.
- `--mode saturate` — everyone pulls flat out. Answers "what is the ceiling",
  and will look worse than any real evening.

## The live table

While it runs it prints a line every five seconds:

```
   time   active  playing  stalled   Mbps    server   errors
      5s      12       12        0    19.2        12       0
     10s      47       46        1    74.1        47       0
     15s     100       88       12   158.6       100       0
     20s     100       61       39    97.3       100       7
```

- **active** — viewers holding an open stream right now.
- **playing** — of those, how many actually pulled bytes in the last five
  seconds. This is the real answer to "how many are watching".
- **stalled** — `active - playing`. Someone whose socket has gone quiet is still
  a live session as far as the server is concerned, and is looking at a frozen
  frame. **This is the column that tells you where the limit is**: throughput
  plateaus long before it does, and a run can look healthy on Mbps alone while
  half the room is buffering.
- **server** — what `GET /api/media/admin/sessions` says is playing, polled
  each tick. Owner-only; a dash means the login is not an owner, which costs
  nothing else.
- **errors** — cumulative, broken down in the summary.

`server` drifting above `active` is a finding in its own right: sessions
outliving their sockets means the owner's live-streams panel is overcounting,
and "end this stream" is aimed at rows nobody is watching.

## What to watch for

| Symptom | Likely cause |
| --- | --- |
| `stream:403` | Sessions being ended, or the guest grant check refusing |
| `decision:5xx` | `PlaybackStarter` failing under concurrency — worth a stack trace |
| Many `TRANSCODE` decisions | The file is not H.264/AAC in MP4; the test skips those, and 100 of them would queue behind `app.media.max-transcode-sessions=2` anyway |
| TTFB p99 in seconds | Tomcat's 200 threads saturated, or the disk seeking |
| Throughput plateaus flat | You have found the pipe — NIC, or upstream if not on the LAN |
| `viewer:IOException` | Connections dropped; check `ulimit -n` and Tomcat's `max-connections` |

## Things it does not do

- **Transcoded titles.** Each viewer that gets a `TRANSCODE` decision is counted
  and skipped. Pulling an HLS playlist properly is a different program, and 100
  transcodes would measure a queue of length 2.
- **Watch parties.** Every viewer is independent; nothing exercises the socket
  or the shared clock.
- **One account.** All viewers share a login and profile. Fine for byte-serving,
  but it does not exercise per-profile progress rows under contention.
