# Test of the live main menu: leave the server, then the server list (the island blurred behind it),
# the title screen at each time of day, and the cherry tree growing again.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
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
click 600 334             # time: morning
shot title-morning 4
click 600 334             # day
click 600 334             # sunset
shot title-sunset 4
click 600 334             # night
shot title-night 4
click 600 334             # back to real time
sleep 3
click 624 334             # grow the tree again
burst title-grow 6 0.45
xdotool mousemove --window "$WID" 1200 668; sleep 1.2
shot title-tooltip 0.3
grep -iE 'catgirl|live main menu' "$G/logs/latest.log" | grep -iE 'warn|error|exception|couldn' | head -20 > "$OUT/title-errors.txt"
