# Getting along with mods you already have: a real fullbright mod (Gamma Utils, G key), AppleSkin
# (our Food Info steps aside) and Zoomify (our zoom steps aside and our C key is freed).
# Switching the Catgirl Fullbright on and off on top must leave their brightness exactly as it was.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
cmd "time set 18000"; cmd "gamerule doDaylightCycle false"; cmd "gamerule advance_time false"
cmd "tp $P 0.5 -60 0.5 180 10"
cmd "summon pig 0.5 -60 -4"
sleep 3
xdotool mousemove --window "$WID" 640 360; sleep 0.3; xdotool click 3; sleep 0.5   # (like the other tests: a first click into the window)
shot 1-night-normal 1
key g; sleep 2            # their fullbright on
shot 2-their-fullbright 1
menu Mods
btn "chip:Visual"; sleep 0.5
shot 3-visual-page 1
btn "card:fullbright" 30; sleep 1.5   # our Fullbright on (you can still choose ours)...
shot 4-ours-on 0.5
btn "card:fullbright" 30; sleep 1.5   # ...and off again: their brightness must come back exactly
key Escape; sleep 1
shot 5-still-their-fullbright 1
menu Mods
btn "chip:All"; sleep 0.5
xdotool mousemove --window "$WID" 640 400; for i in 1 2 3 4 5 6 7 8; do xdotool click 5; sleep 0.15; done
shot 6-all-scrolled 0.8
key Escape; sleep 0.5
grep -E "^gamma|catgirl|gammautils" "$G/options.txt" > "$OUT/keys-and-gamma.txt" 2>/dev/null
cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
cp "$G"/config/gammautils*.json "$OUT/" 2>/dev/null
