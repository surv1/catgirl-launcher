# Test of Catgirl-style menus: the pause menu (with the cat), options, video settings (sliders and
# the list), the inventory's dim, the server list (the picked-row box), Add Server (text boxes), the
# title screen, Create World (tabs) and the telemetry screen (a tick box).
# Buttons are found by their label: the mod logs every screen's buttons while testing.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
hover() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.5; }
where() { # x y w h of the button labelled $1 on the newest screen
  grep -a '\[Catgirl\] widget "' "$G/logs/latest.log" | grep -aF "widget \"$1\"" | tail -1 | sed -E 's/.*" (-?[0-9]+) (-?[0-9]+) ([0-9]+) ([0-9]+).*/\1 \2 \3 \4/'
}
btn() {
  local r; r=$(where "$1")
  if [ -z "$r" ]; then log "no button \"$1\""; return 1; fi
  set -- $r
  click $(( $1 + $3 / 2 )) $(( $2 + $4 / 2 ))
}
point() {
  local r; r=$(where "$1"); [ -z "$r" ] && return 1
  set -- $r
  hover $(( $1 + $3 / 2 )) $(( $2 + $4 / 2 ))
}
cmd "time set 6000"; cmd "weather clear"
sleep 2
key Escape; sleep 1
shot pause 1.5
point "Options..."
shot pause-hover 0.6
btn "Options..."
shot options 1.2
btn "Video Settings..."
shot video 1.2
hover 200 96
shot video-hover 0.6
key Escape; key Escape; key Escape
sleep 1
key e
shot inventory 1.2
key Escape
cmd "kick $P Back to the menu"
sleep 3
key Tab; key Return       # "Back to server list"
sleep 2
click 320 52              # the "Scanning for games" row
shot server-list 1.5
btn "Add Server"
shot add-server 1.2
xdotool type --delay 60 "Catgirl Island"; sleep 0.3
shot add-server-typed 0.6
key Escape
sleep 1
key Escape
sleep 2
shot title 2.5
point "Singleplayer"
shot title-hover 0.8
btn "Singleplayer"
sleep 2
shot create-world 1.5
hover 300 12
shot create-world-tab 0.6
key Escape
sleep 2
btn "Options..."
shot options-title 1.2
btn "Language..."
shot language 1.5
key Escape
sleep 1
btn "Telemetry Data..."
shot telemetry 1.5
key Escape; key Escape
sleep 1
grep -iE 'catgirl' "$G/logs/latest.log" | grep -iE 'warn|error|exception|couldn|switched off' | head -20 > "$OUT/screens-errors.txt"
grep -a '\[Catgirl\] widget "' "$G/logs/latest.log" | sed -E 's/.*\[Catgirl\] //' > "$OUT/widgets.txt"
