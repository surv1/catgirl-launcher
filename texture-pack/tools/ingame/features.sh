# Test of the Catgirl mods (Lunar-style): HUD pieces, crosshair, scoreboard/boss bar size, fullbright
# at midnight, hit reach/combo/particles, snaplook, zoom, and a mod's settings page.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
rclick() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 3; sleep 0.5; }
cmd "gamerule doDaylightCycle false"; cmd "gamerule advance_time false"; cmd "weather rain"
cmd "tp $P 0.5 -60 0.5 180 10"
cmd "gamemode survival $P"
cmd "clear $P"
cmd "item replace entity $P hotbar.0 with diamond_sword"
cmd "give $P totem_of_undying 3"; cmd "give $P ender_pearl 16"; cmd "give $P golden_apple 5"; cmd "give $P arrow 64"
cmd "item replace entity $P armor.head with iron_helmet"; cmd "item replace entity $P armor.chest with iron_chestplate"
cmd "effect give $P speed 600 1"; cmd "effect give $P strength 300 0"; cmd "effect give $P fire_resistance 900 0"
cmd "scoreboard objectives add cgtest dummy \"Catgirl Test\""; cmd "scoreboard objectives setdisplay sidebar cgtest"
cmd "scoreboard players set $P cgtest 42"; cmd "scoreboard players set Kitty cgtest 7"
cmd "bossbar add cg \"Boss Test\""; cmd "bossbar set cg players $P"
cmd "summon pig 0.5 -60 -2.2 {NoAI:1b,Silent:1b,Health:500f,attributes:[{id:\"minecraft:max_health\",base:500}]}"
cmd "time set 18000"
sleep 3
shot hud-all 2
# hit the pig a few times: reach, combo, particles
xdotool mousemove --window "$WID" 640 360
for i in 1 2 3 4; do xdotool click 1; sleep 0.65; done
shot hits 0.1
xdotool keydown v; sleep 0.8
shot snaplook 0.2
xdotool keyup v; sleep 0.5
xdotool keydown c; sleep 1.2
shot zoom-10 0.2
xdotool keyup c; sleep 0.8
# a mod's settings page: Visual category, right-click Crosshair
key Shift_R; sleep 1
click 320 118          # Mods petal
click 301 67           # Visual
shot visual-category 1
rclick 230 110         # Crosshair -> settings
shot crosshair-settings 1
click 300 96; click 300 96; click 300 96   # style: dot, circle, cross + dot
click 483 157          # pink
shot crosshair-settings-changed 1
key Escape; key Escape
shot crosshair-pink 1.5
cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
cp "$G/options.txt" "$OUT/options-after.txt" 2>/dev/null
