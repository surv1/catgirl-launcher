#!/usr/bin/env bash
# Takes real in-game screenshots: a local server + the Fabric client with the Catgirl mod and pack.
# Run by .github/workflows/ingame-shots.yml. Needs: $G (game dir with mods/resourcepacks set up),
# server.jar in $S, java 21 on PATH, xvfb-run, xdotool, portablemc. Screenshots end up in $OUT.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
MC=${MC:-1.21.11}
NAME=CatTester
UUID=$(python3 -c "
import hashlib
b=bytearray(hashlib.md5(b'OfflinePlayer:$NAME').digest()); b[6]=(b[6]&0x0f)|0x30; b[8]=(b[8]&0x3f)|0x80
h=b.hex(); print(f'{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}')")
mkdir -p "$OUT"
log() { echo "[shots] $*"; }

# ---- server
cd "$S"
echo "eula=true" > eula.txt
cat > server.properties <<EOF
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
generate-structures=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
gamemode=creative
difficulty=peaceful
view-distance=4
simulation-distance=4
spawn-protection=0
allow-flight=true
motd=catgirl test
EOF
echo "[{\"uuid\":\"$UUID\",\"name\":\"$NAME\",\"level\":4,\"bypassesPlayerLimit\":false}]" > ops.json
rm -f srv.in; mkfifo srv.in
java -Xmx1G -jar server.jar nogui < srv.in > server.log 2>&1 &
SERVER_PID=$!
exec 3> srv.in
cmd() { echo "$*" >&3; sleep 0.4; }
for i in $(seq 1 180); do grep -q 'Done (' server.log && break; sleep 1; done
grep -q 'Done (' server.log || { tail -40 server.log; log "server did not start"; exit 1; }
log "server up"

# ---- client
cd "$G"
mkdir -p config
cat > config/catgirl-cosmetics.json <<EOF
{"api":"","uuid":"$UUID","items":{
 "ears":{"on":true,"color":"#5a3a28","inner":"#fff4ea"},
 "tail":{"on":true,"color":"#5a3a28"}}}
EOF
cat > options.txt <<'EOF'
resourcePacks:["vanilla","fabric","file/CatgirlPack.zip"]
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
pauseOnLostFocus:false
renderDistance:4
simulationDistance:5
guiScale:2
fov:-0.25
soundCategory_master:0.0
narrator:0
realmsNotifications:false
EOF
export LIBGL_ALWAYS_SOFTWARE=1
Xvfb :99 -screen 0 1280x720x24 > /dev/null 2>&1 &
XVFB=$!
export DISPLAY=:99
sleep 2
portablemc --main-dir "$G" --work-dir "$G" start "fabric:$MC" -u "$NAME" -i "$UUID" -s localhost > "$G/client.txt" 2>&1 &
CLIENT=$!
RC=0
for i in $(seq 1 480); do grep -q "$NAME joined the game" "$S/server.log" && break; kill -0 $CLIENT 2>/dev/null || break; sleep 1; done
if ! grep -q "$NAME joined the game" "$S/server.log"; then
  log "client never joined"; tail -80 "$G/client.txt"; RC=1
else
  log "joined"
  sleep 25
  WID=$(xdotool search --name 'Minecraft|CatGirl|Client' | head -1)
  log "window $WID: $(xdotool getwindowname "$WID")"
  xdotool windowfocus --sync "$WID" 2>/dev/null
  key() { xdotool windowfocus "$WID" 2>/dev/null; xdotool key "$1"; sleep 0.6; }
  n=0
  shot() {
    n=$((n+1)); sleep "${2:-2}"
    local name; name="$OUT/$(printf %02d $n)-$1.png"
    key F2; sleep 1.5
    local f; f=$(ls -t "$G"/screenshots/*.png 2>/dev/null | head -1)
    if [ -n "$f" ]; then mv "$f" "$name"; log "$1"; else import -window root "$name"; log "$1 (X capture)"; fi
  }
  source "$HERE/scenario.sh"
fi
kill $CLIENT 2>/dev/null; sleep 2; kill $XVFB 2>/dev/null
cmd stop; sleep 5; kill $SERVER_PID 2>/dev/null
cp "$S/server.log" "$OUT/server.log"; cp "$G/logs/latest.log" "$OUT/client.log" 2>/dev/null
grep -E '/(WARN|ERROR)\]' "$G/logs/latest.log" | grep -viE 'realms|narrator|SoundSystem|user properties|OpenAL' | head -40 > "$OUT/warnings.txt"
exit $RC
