# Catgirl-style menus with a resource pack that brings its own buttons: ours are drawn over them.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
key Escape; sleep 1
shot pause 1.5
key Escape
cmd "kick $P Back to the menu"
sleep 3
key Tab; key Return       # "Back to server list"
sleep 2
click 320 52
shot server-list 1.5
key Escape
sleep 2
shot title 2.5
grep -a "ButtonsPack\|Catgirl-style" "$G/logs/latest.log" | head -10 > "$OUT/pack-log.txt"
