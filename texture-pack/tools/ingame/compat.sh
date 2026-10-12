# Getting along with mods you already have: a real fullbright mod (brightness far above 100%),
# AppleSkin (our Food Info steps aside) and a zoom mod (our zoom steps aside, our C key is freed).
# The Catgirl zoom/fullbright code must not reset the other mod's brightness.
P=CatTester
cmd "time set 18000"; cmd "gamerule doDaylightCycle false"; cmd "gamerule advance_time false"
cmd "tp $P 0.5 -60 0.5 180 10"
cmd "summon pig 0.5 -60 -4"
sleep 3
shot 1-night-with-their-fullbright 1
# hold C: with a zoom mod installed, that's theirs now (ours is unbound)
xdotool keydown c; sleep 1.2
shot 2-holding-c 0.2
xdotool keyup c; sleep 1
shot 3-after-c 0.5
# the Catgirl menu: Food Info / Zoom / Fullbright should say "using your ..."
key Shift_R; sleep 1
click 320 118
click 196 67
shot 4-mods 1
xdotool mousemove --window "$WID" 640 400; for i in 1 2 3 4 5 6 7 8; do xdotool click 5; sleep 0.15; done
shot 5-mods-scrolled 0.8
key Escape; sleep 0.5
shot 6-still-bright 1
cp "$G/options.txt" "$OUT/options-after.txt" 2>/dev/null
grep -E "^gamma|key_key" "$G/options.txt" > "$OUT/keys-and-gamma.txt" 2>/dev/null
cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
