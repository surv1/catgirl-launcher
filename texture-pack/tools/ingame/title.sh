# Test of the live main menu: leave the server, then the server list (the island blurred behind it),
# the title screen, the Scene panel (time of day, trees, camera) and the trees growing again.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
where() { grep -a '\[Catgirl\] widget "' "$G/logs/latest.log" | grep -aF "widget \"$1\"" | tail -1 | sed -E 's/.*" (-?[0-9]+) (-?[0-9]+) ([0-9]+) ([0-9]+).*/\1 \2 \3 \4/'; }
btn() { local r; r=$(where "$1"); if [ -z "$r" ]; then log "no button \"$1\""; return 1; fi; set -- $r; click $(( $1 + $3 / 2 )) $(( $2 + $4 / 2 )); }
burst() {
  local name=$1 count=$2 gap=$3 i f
  for i in $(seq 1 "$count"); do xdotool key F2; sleep "$gap"; done
  sleep 2.5
  i=0
  for f in $(ls -tr "$G"/screenshots/*.png 2>/dev/null | tail -n "$count"); do
    i=$((i+1)); n=$((n+1)); mv "$f" "$OUT/$(printf %02d $n)-$name-$i.png"
  done
  log "$name x$i"
}
shot in-game 1
cmd "kick $P Back to the menu"
sleep 3
shot kicked 0.5
key Tab; key Return       # "Back to server list"
sleep 2
shot server-list 2
key Escape
sleep 2
shot title-now 3
btn "Scene"
shot scene-panel 1
btn "scene:morning";  shot title-morning 4
btn "scene:sunset";   shot title-sunset 4
btn "scene:night";    shot title-night 4
btn "scene:day"
btn "scene:oak";      shot trees-oak 4.5
btn "scene:birch";    shot trees-birch 4.5
btn "scene:spruce";   shot trees-spruce 4.5
btn "scene:mixed";    shot trees-mixed 4.5
btn "scene:still"
btn "scene:cherry"
btn "scene:sunset";   shot camera-still 4.5
btn "scene:sway";     shot camera-sway 3
btn "scene:orbit"
btn "scene:real time"
btn "Scene"           # close it
sleep 2
btn "grow"
burst title-grow 6 0.45
xdotool mousemove --window "$WID" 1200 668; sleep 1.2
shot title-tooltip 0.3
grep -iE 'catgirl|live main menu' "$G/logs/latest.log" | grep -iE 'warn|error|exception|couldn' | head -20 > "$OUT/title-errors.txt"
grep -a '\[Catgirl\] scene ' "$G/logs/latest.log" | tail -20 > "$OUT/scene-log.txt"
