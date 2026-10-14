# Test of Configs: the built-in ones, loading PvP and Undo, saving your setup and naming it,
# copying its code and pasting it back in.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
cmd "time set 6000"; cmd "weather clear"; cmd "tp $P 0.5 -60 0.5 180 0"
sleep 2
shot before 1
key Shift_R; sleep 1
click 320 118          # Mods petal
click 89 115           # Configs (sidebar)
shot configs-page 1
click 254 137          # PvP
shot configs-pvp 0.8
click 383 323          # Load
shot configs-pvp-loaded 0.8
key Escape; key Escape; sleep 0.5
shot pvp-in-game 1.5
key Shift_R; sleep 1
click 320 118; click 89 115
click 254 137          # PvP again: In use
shot configs-in-use 0.8
click 254 93           # Cosy
click 383 323          # Load
shot configs-cosy-loaded 0.8
click 379 301          # Undo
shot configs-undone 0.8
click 217 57           # Save current setup (starts naming it)
xdotool type --delay 60 "Night run"; sleep 0.3
key Return
shot configs-saved 0.8
click 424 323          # Copy
shot configs-copied 0.6
click 312 57           # Paste code
shot configs-pasted 0.8
click 559 323          # Delete
shot configs-delete-sure 0.5
click 559 323          # Sure?
shot configs-deleted 0.8
key Escape; key Escape; sleep 0.5
cp "$G/config/catgirl-configs.json" "$OUT/catgirl-configs.json" 2>/dev/null
cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
