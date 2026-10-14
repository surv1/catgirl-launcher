# Test of Configs: the built-in ones, loading PvP and Undo, saving your setup and naming it,
# copying its code and pasting it back in.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
cmd "time set 6000"; cmd "weather clear"; cmd "tp $P 0.5 -60 0.5 180 0"
sleep 2
shot before 1
menu Configs
shot configs-page 1
btn "cfg:PvP"
shot configs-pvp 0.8
btn "cfg:load"
shot configs-pvp-loaded 0.8
key Escape; key Escape; sleep 0.5
shot pvp-in-game 1.5
menu Configs
btn "cfg:PvP"          # PvP again: In use
shot configs-in-use 0.8
btn "cfg:Cosy"
btn "cfg:load"
shot configs-cosy-loaded 0.8
btn "cfg:undo"
shot configs-undone 0.8
btn "cfg:save"           # Save current setup (starts naming it)
xdotool type --delay 60 "Night run"; sleep 0.3
key Return
shot configs-saved 0.8
btn "cfg:copy"
shot configs-copied 0.6
btn "cfg:paste"
shot configs-pasted 0.8
btn "cfg:delete"
shot configs-delete-sure 0.5
btn "cfg:delete"        # Sure?
shot configs-deleted 0.8
key Escape; key Escape; sleep 0.5
cp "$G/config/catgirl-configs.json" "$OUT/catgirl-configs.json" 2>/dev/null
cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
