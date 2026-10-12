# Getting along with mods you already have: a real fullbright mod (Gamma Utils, G key), AppleSkin
# (our Food Info steps aside) and Zoomify (our zoom steps aside and our C key is freed).
# Switching the Catgirl Fullbright on and off on top must leave their brightness exactly as it was.
P=CatTester
cmd "time set 18000"; cmd "gamerule doDaylightCycle false"; cmd "gamerule advance_time false"
cmd "tp $P 0.5 -60 0.5 180 10"
cmd "summon pig 0.5 -60 -4"
sleep 3
shot 1-night-normal 1
key g; sleep 2            # their fullbright on
shot 2-their-fullbright 1
key Shift_R; sleep 2.5
click 320 118; sleep 1     # Mods
click 301 67; sleep 0.5    # Visual
shot 3-visual-page 1
click 520 110; sleep 1.5   # our Fullbright on (you can still choose ours)...
shot 4-ours-on 0.5
click 520 110; sleep 1.5   # ...and off again: their brightness must come back exactly
key Escape; sleep 1
shot 5-still-their-fullbright 1
key Shift_R; sleep 2.5
click 320 118; sleep 1
click 196 67; sleep 0.5    # All
xdotool mousemove --window "$WID" 640 400; for i in 1 2 3 4 5 6 7 8; do xdotool click 5; sleep 0.15; done
shot 6-all-scrolled 0.8
key Escape; sleep 0.5
grep -E "^gamma|catgirl|gammautils" "$G/options.txt" > "$OUT/keys-and-gamma.txt" 2>/dev/null
cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
cp "$G"/config/gammautils*.json "$OUT/" 2>/dev/null
