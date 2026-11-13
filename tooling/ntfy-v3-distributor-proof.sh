#!/usr/bin/env bash
# Coordinated host side only. Run after the test target is ready, ntfy is listening on
# 127.0.0.1:13587, and adb reverse is configured for the target serial.
set -euo pipefail
umask 077

: "${SERIAL:?set to the emulator/device serial}"
: "${PHONE_USER_ID:?set to the isolated app Android user ID}"
: "${PHONE_FILES:?set to /data/user/$PHONE_USER_ID/im.collie.app.imo/files}"
: "${OUT:?set to a new private run directory}"
: "${SOCKET:?set to the source-pinned CollieSocket sender binary}"
[[ "$PHONE_USER_ID" =~ ^[0-9]+$ ]] || { echo "Invalid PHONE_USER_ID" >&2; exit 2; }
ORIGIN=http://127.0.0.1:13587
EXPECTED_PHONE_FILES="/data/user/$PHONE_USER_ID/im.collie.app.imo/files"
[[ "$PHONE_FILES" == "$EXPECTED_PHONE_FILES" ]] || { echo "Refusing unexpected app-private path for user $PHONE_USER_ID: $PHONE_FILES" >&2; exit 2; }
[[ -x "$SOCKET" ]] || { echo "Socket sender binary missing" >&2; exit 2; }
if [[ -e "$OUT" && -n "$(find "$OUT" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo "Refusing nonempty run directory: $OUT" >&2
  exit 2
fi
mkdir -p "$OUT/state"
chmod 700 "$OUT" "$OUT/state"

ADB=(adb -s "$SERIAL")
APP_UID=$("${ADB[@]}" shell cmd package list packages -U --user "$PHONE_USER_ID" im.collie.app.imo | sed -n 's/^package:im\.collie\.app\.imo uid:\([0-9][0-9]*\)\r*$/\1/p')
[[ "$APP_UID" =~ ^[0-9]+$ ]] || { echo "Cannot resolve the isolated Collie UID for user $PHONE_USER_ID" >&2; exit 2; }
NTFY_UID=$("${ADB[@]}" shell cmd package list packages -U --user "$PHONE_USER_ID" io.heckel.ntfy | sed -n 's/^package:io\.heckel\.ntfy uid:\([0-9][0-9]*\)\r*$/\1/p')
[[ "$NTFY_UID" =~ ^[0-9]+$ ]] || { echo "Cannot resolve the ntfy UID for user $PHONE_USER_ID" >&2; exit 2; }
LOGCAT_PID=
NTFY_LOGCAT_PID=
stop_logcat() {
  if [[ -n "$LOGCAT_PID" ]]; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
    LOGCAT_PID=
  fi
  if [[ -n "$NTFY_LOGCAT_PID" ]]; then
    kill "$NTFY_LOGCAT_PID" 2>/dev/null || true
    wait "$NTFY_LOGCAT_PID" 2>/dev/null || true
    NTFY_LOGCAT_PID=
  fi
}
trap stop_logcat EXIT

pull_phone_file() {
  local name=$1
  local remote="files/phoneproof/$name"
  "${ADB[@]}" exec-out run-as im.collie.app.imo --user "$PHONE_USER_ID" sh -c 'test -f "$1" || exit 44; exec cat "$1"' sh "$remote" > "$OUT/$name"
  [[ -s "$OUT/$name" ]] || { echo "Phone marker/export is empty: $name" >&2; return 1; }
  ! grep -qE '^(cat:|run-as:|error:)' "$OUT/$name" || { echo "Phone marker/export contains a transport error: $name" >&2; return 1; }
}
push_phone_file() {
  local name=$1
  local remote="files/phoneproof/$name"
  "${ADB[@]}" shell run-as im.collie.app.imo --user "$PHONE_USER_ID" tee "$remote" < "$OUT/$name" >/dev/null
  "${ADB[@]}" shell run-as im.collie.app.imo --user "$PHONE_USER_ID" /system/bin/chmod 600 "$remote" >/dev/null
  "${ADB[@]}" exec-out run-as im.collie.app.imo --user "$PHONE_USER_ID" cat "$remote" > "$OUT/verify-$name"
  cmp -s "$OUT/$name" "$OUT/verify-$name" || { echo "Phone file verification failed: $name" >&2; return 1; }
}
wait_phone_file() {
  local name=$1 deadline=$((SECONDS + 180))
  while (( SECONDS < deadline )); do
    if pull_phone_file "$name" 2>"$OUT/adb-pull.err" && [[ -s "$OUT/$name" ]] && ! grep -q 'No such file or directory' "$OUT/$name"; then
      return 0
    fi
    sleep 2
  done
  cat "$OUT/adb-pull.err" >&2
  echo "Timed out waiting for phone marker: $name" >&2
  return 1
}
wait_app_log() {
  local label=$1 needle=$2 deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    if grep -Fq -- "$needle" "$OUT/app-uid-live.log" 2>/dev/null; then
      printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$label" >> "$OUT/app-log-events.txt"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for scoped Collie log for user $PHONE_USER_ID: $label" >&2
  return 1
}
wait_ntfy_receipt() {
  local label=$1 id=$2 deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    if grep -Fq -- "Received notification: Notification(id=$id," "$OUT/ntfy-uid-live.log" 2>/dev/null; then
      printf '%s %s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$label" "$id" >> "$OUT/ntfy-receipts.txt"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for ntfy receipt for user $PHONE_USER_ID: $label" >&2
  return 1
}

# Owner starts the app test. This runner only copies its exact synthetic export/markers.
wait_phone_file phone.ready
pull_phone_file phone.context-state.txt
pull_phone_file colliesocket-enrollment.json
"${ADB[@]}" logcat -T 1 --uid="$APP_UID" -v time > "$OUT/app-uid-live.log" 2> "$OUT/app-logcat.err" &
LOGCAT_PID=$!
"${ADB[@]}" logcat -T 1 --uid="$NTFY_UID" -v time > "$OUT/ntfy-uid-live.log" 2> "$OUT/ntfy-logcat.err" &
NTFY_LOGCAT_PID=$!
chmod 600 "$OUT/app-uid-live.log" "$OUT/app-logcat.err" "$OUT/ntfy-uid-live.log" "$OUT/ntfy-logcat.err"
sleep 1
kill -0 "$LOGCAT_PID" 2>/dev/null && kill -0 "$NTFY_LOGCAT_PID" 2>/dev/null || { echo "Scoped app log capture did not start" >&2; exit 1; }
chmod 600 "$OUT/colliesocket-enrollment.json"
readarray -t REG < <(python3 -I - "$OUT/colliesocket-enrollment.json" <<'PY'
import json,re,sys,urllib.parse
x=json.load(open(sys.argv[1]))
assert x.get("enrollment_version") == 3
account=x["account_id"]
assert re.fullmatch(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", account)
u=urllib.parse.urlsplit(x["subscription"]["endpoint"])
assert f"{u.scheme}://{u.netloc}" == "http://127.0.0.1:13587"
assert u.path.startswith("/up") and u.query == "up=1"
print(account)
print(u.path.removeprefix("/"))
PY
)
ACCOUNT_ID=${REG[0]}
TOPIC=${REG[1]}
DB="$OUT/state/colliesocket.db"
CONFIG="$OUT/config.toml"
cat > "$CONFIG" <<EOF_CONFIG
db = "$DB"
host = "127.0.0.1"
port = 8030
ready_port = 8031
webserver = false
push_only = true
signal_env = "Staging"
allowed_uuids = ["$ACCOUNT_ID"]
allowed_endpoints = ["$ORIGIN"]
legacy_wake_until = 0
EOF_CONFIG
chmod 600 "$CONFIG"
M() { "$SOCKET" -c "$CONFIG" "$@"; }
M migrate >/dev/null
M enrollment import --file "$OUT/colliesocket-enrollment.json" >"$OUT/import-receipt.json"
M enrollment status "$ACCOUNT_ID" >"$OUT/enrollment-status.json"
printf 'import accepted; status checked\n' > "$OUT/host.imported"
push_phone_file host.imported

# A fresh v3 import queues an enrollment_imported status. Drain it before the wake.
M push drain --once >"$OUT/status-drain.json"
python3 -I - "$OUT/status-drain.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))["processed"] is True
PY
wait_phone_file phone.imported

# The fresh ntfy topic should now contain only the delivered enrollment status.
curl -fsS "$ORIGIN/$TOPIC/json?poll=1&since=all" > "$OUT/ntfy-after-status.json"
python3 -I - "$OUT/ntfy-after-status.json" "$OUT/status-ntfy-id.txt" <<'PY'
import json,sys
ms=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
ms=[m for m in ms if m.get("event")=="message"]
assert len(ms)==1, f"expected one status message before wake; got {len(ms)}"
open(sys.argv[2],"w").write(ms[0]["id"]+"\n")
PY
wait_ntfy_receipt enrollment-status "$(cat "$OUT/status-ntfy-id.txt")"

M push wake "$ACCOUNT_ID" >"$OUT/wake-queued.json"
python3 -I - "$OUT/wake-queued.json" "$OUT/wake-event-id.txt" <<'PY'
import json,re,sys
x=json.load(open(sys.argv[1]))
assert x["queued"] is True
assert re.fullmatch(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}",x["event_id"])
open(sys.argv[2],"w").write(x["event_id"]+"\n")
PY
chmod 600 "$OUT/wake-event-id.txt"
M push drain --once >"$OUT/wake-drain.json"
python3 -I - "$OUT/wake-drain.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))["processed"] is True
PY
curl -fsS "$ORIGIN/$TOPIC/json?poll=1&since=all" > "$OUT/ntfy-after-wake.json"
python3 -I - "$OUT/ntfy-after-wake.json" "$OUT/status-ntfy-id.txt" "$OUT/wake.bin" "$OUT/wake-metadata.json" <<'PY'
import base64,hashlib,json,sys
items=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
items=[m for m in items if m.get("event")=="message"]
status_id=open(sys.argv[2]).read().strip()
new=[m for m in items if m.get("id") != status_id]
assert len(new)==1, f"expected one message after enrollment status; got {len(new)}"
m=new[0]
assert m.get("encoding")=="base64"
b=base64.b64decode(m["message"],validate=True)
assert len(b)>200
open(sys.argv[3],"wb").write(b)
meta={"ntfy_message_id":m["id"],"time":m.get("time"),"bytes":len(b),"sha256":hashlib.sha256(b).hexdigest()}
open(sys.argv[4],"w").write(json.dumps(meta,sort_keys=True)+"\n")
PY
chmod 600 "$OUT/wake.bin" "$OUT/wake-metadata.json"
WAKE_NTFY_ID=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["ntfy_message_id"])' "$OUT/wake-metadata.json")
wait_ntfy_receipt wake "$WAKE_NTFY_ID"
printf 'host event id: %s\n' "$(cat "$OUT/wake-event-id.txt")" > "$OUT/host.valid-sent"
push_phone_file wake-event-id.txt
push_phone_file host.valid-sent
wait_app_log ACCEPT_WAKE 'Encrypted push: ACCEPT_WAKE'
printf 'production receiver ACCEPT_WAKE observed\n' > "$OUT/host.valid-observed"
push_phone_file host.valid-observed
wait_phone_file phone.valid-accepted

# Preserve the original wake bytes. Tamper only byte 200, then replay the original exactly.
python3 -I - "$OUT/wake.bin" "$OUT/wake-tampered.bin" <<'PY'
import sys
b=bytearray(open(sys.argv[1],"rb").read())
b[200]^=1
open(sys.argv[2],"wb").write(b)
PY
curl -fsS -X POST --data-binary @"$OUT/wake-tampered.bin" -H 'Content-Type: application/octet-stream' -o /dev/null -w '%{http_code}\n' "$ORIGIN/$TOPIC?up=1" > "$OUT/tamper-http-status.txt"
[[ $(cat "$OUT/tamper-http-status.txt") == 2* ]] || { echo "ntfy rejected tampered post" >&2; exit 1; }
curl -fsS "$ORIGIN/$TOPIC/json?poll=1&since=all" > "$OUT/ntfy-after-tamper.json"
python3 -I - "$OUT/ntfy-after-tamper.json" "$OUT/status-ntfy-id.txt" "$OUT/wake-metadata.json" "$OUT/wake-tampered.bin" "$OUT/tamper-metadata.json" <<'PY'
import base64,hashlib,json,sys
items=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
items=[m for m in items if m.get("event")=="message"]
known={open(sys.argv[2]).read().strip(),json.load(open(sys.argv[3]))["ntfy_message_id"]}
new=[m for m in items if m.get("id") not in known]
assert len(new)==1, f"expected one ntfy ciphertext after wake; got {len(new)}"
b=base64.b64decode(new[0]["message"],validate=True)
assert b==open(sys.argv[4],"rb").read(), "ntfy tamper delivery differed from posted bytes"
open(sys.argv[5],"w").write(json.dumps({"ntfy_message_id":new[0]["id"],"bytes":len(b),"sha256":hashlib.sha256(b).hexdigest()},sort_keys=True)+"\n")
PY
chmod 600 "$OUT/tamper-metadata.json"
TAMPER_NTFY_ID=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["ntfy_message_id"])' "$OUT/tamper-metadata.json")
wait_ntfy_receipt tamper "$TAMPER_NTFY_ID"
printf 'posted one-bit-flipped original wake\n' > "$OUT/host.tamper-sent"
push_phone_file host.tamper-sent
wait_app_log DROP_NOT_DECRYPTED 'Could not decrypt message'
printf 'connector rejected tampered ciphertext\n' > "$OUT/host.tamper-observed"
push_phone_file host.tamper-observed
wait_phone_file phone.tamper-rejected

curl -fsS -X POST --data-binary @"$OUT/wake.bin" -H 'Content-Type: application/octet-stream' -o /dev/null -w '%{http_code}\n' "$ORIGIN/$TOPIC?up=1" > "$OUT/replay-http-status.txt"
[[ $(cat "$OUT/replay-http-status.txt") == 2* ]] || { echo "ntfy rejected replay post" >&2; exit 1; }
curl -fsS "$ORIGIN/$TOPIC/json?poll=1&since=all" > "$OUT/ntfy-after-replay.json"
python3 -I - "$OUT/ntfy-after-replay.json" "$OUT/status-ntfy-id.txt" "$OUT/wake-metadata.json" "$OUT/tamper-metadata.json" "$OUT/wake.bin" "$OUT/replay-metadata.json" <<'PY'
import base64,hashlib,json,sys
items=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
items=[m for m in items if m.get("event")=="message"]
known={open(sys.argv[2]).read().strip(),json.load(open(sys.argv[3]))["ntfy_message_id"],json.load(open(sys.argv[4]))["ntfy_message_id"]}
new=[m for m in items if m.get("id") not in known]
assert len(new)==1, f"expected one ntfy ciphertext after tamper; got {len(new)}"
b=base64.b64decode(new[0]["message"],validate=True)
assert b==open(sys.argv[5],"rb").read(), "ntfy replay delivery differed from original bytes"
open(sys.argv[6],"w").write(json.dumps({"ntfy_message_id":new[0]["id"],"bytes":len(b),"sha256":hashlib.sha256(b).hexdigest()},sort_keys=True)+"\n")
PY
chmod 600 "$OUT/replay-metadata.json"
REPLAY_NTFY_ID=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["ntfy_message_id"])' "$OUT/replay-metadata.json")
wait_ntfy_receipt replay "$REPLAY_NTFY_ID"
printf 'posted byte-identical original wake\n' > "$OUT/host.replay-sent"
push_phone_file host.replay-sent
wait_app_log DROP_DUPLICATE 'Encrypted push: DROP_DUPLICATE'
printf 'production receiver DROP_DUPLICATE observed\n' > "$OUT/host.replay-observed"
push_phone_file host.replay-observed
wait_phone_file phone.replay-rejected

for name in phone.valid-accepted phone.tamper-rejected phone.replay-rejected state-valid.txt state-after-tamper.txt state-after-replay.txt; do
  pull_phone_file "$name"
done
stop_logcat
rm -f "$OUT/app-uid-live.log" "$OUT/app-logcat.err" "$OUT/ntfy-uid-live.log" "$OUT/ntfy-logcat.err"
printf 'Android target stages complete; inspect only nonsecret markers/state and wake-metadata.json\n'
