# Test of the Catgirl mods (Lunar-style): HUD pieces, crosshair, scoreboard/boss bar size, fullbright
# at midnight, hit reach/combo/particles, snaplook, zoom, and a mod's settings page.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
rclick() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 3; sleep 0.5; }
cmd "gamerule doDaylightCycle false"; cmd "gamerule advance_time false"; cmd "weather rain"
cmd "tp $P 0.5 -60 0.5 180 28"
cmd "gamemode survival $P"
cmd "clear $P"
cmd "item replace entity $P hotbar.0 with diamond_sword"
cmd "give $P totem_of_undying 3"; cmd "give $P ender_pearl 16"; cmd "give $P golden_apple 5"; cmd "give $P arrow 64"
cmd "give $P pink_shulker_box[container=[{slot:0,item:{id:diamond,count:64}},{slot:1,item:{id:golden_apple,count:12}},{slot:4,item:{id:totem_of_undying,count:1}},{slot:13,item:{id:ender_pearl,count:16}},{slot:26,item:{id:netherite_sword,count:1}}]]"
cmd "item replace entity $P weapon.offhand with shield"
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
xdotool click 1; sleep 0.05
shot hitcolor 0
sleep 0.8
# waypoint here, then walk away (tp) and look back at it
key b; sleep 0.5
cmd "tp $P 0.5 -60 14.5 180 10"; sleep 2
shot waypoint 0.5
# block outline: look down at the floor
cmd "tp $P 0.5 -60 14.5 180 65"; sleep 1.5
shot outline 0.3
# freelook: hold Left Alt and move the mouse; the camera turns, the player doesn't
cmd "tp $P 0.5 -60 3.5 180 15"; sleep 1.5
xdotool keydown Alt_L; sleep 0.4
for i in 1 2 3 4 5 6; do xdotool mousemove_relative -- 60 0; sleep 0.1; done
shot freelook 0.3
xdotool keyup Alt_L; sleep 0.8
shot freelook-after 0.3
cmd "tp $P 0.5 -60 0.5 180 28"; sleep 1
xdotool keydown v; sleep 0.8
shot snaplook 0.2
xdotool keyup v; sleep 0.5
xdotool keydown c; sleep 1.2
shot zoom-10 0.2
xdotool keyup c; sleep 0.8
# a mod's settings page: Visual category, right-click Crosshair
key Shift_R; sleep 1
click 320 118          # Mods petal
shot mods-icons 1
click 384 55           # Visual
shot visual-category 1
rclick 213 104         # Crosshair -> settings
shot crosshair-settings 1
click 425 81; click 425 81; click 425 81   # style: dot, circle, cross + dot
click 421 169          # colour -> the colour picker
shot colour-picker 0.8
click 351 247          # pink preset
key Escape             # close the picker
shot crosshair-settings-changed 1
key Escape; key Escape
shot crosshair-pink 1.5
# ---- built-in add-ons
# Food Info: get hungry (needs a difficulty above peaceful), then hold the golden apples
# hotbar: 0 sword, 1-3 totems (they don't stack), 4 pearls, 5 gapples, 6 arrows, 7 shulker box
cmd "kill @e[type=!player,type=!pig]"; cmd "difficulty easy"; cmd "effect give $P hunger 8 150"; sleep 9
cmd "effect clear $P hunger"; cmd "difficulty peaceful"; cmd "kill @e[type=!player,type=!pig]"
key 6; sleep 0.5
shot food-preview 0.3
key 1
# Shulker Peek: open the inventory and hover the shulker box
key e; sleep 1
xdotool mousemove --window "$WID" $(( (248 + 7 * 18) * 2 )) $(( 247 * 2 )); sleep 0.6
shot shulker-peek 0.3
# Mouse Tweaks: scroll down over the pearls twice: two pearls move up into the inventory
xdotool mousemove --window "$WID" $(( (248 + 4 * 18) * 2 )) $(( 247 * 2 )); sleep 0.4
xdotool click 5; sleep 0.4; xdotool click 5; sleep 0.6
xdotool mousemove --window "$WID" 40 40; sleep 0.4
shot mousetweaks-scroll 0.3
key Escape
# ---- a mod's settings, with the Look section (scrolls)
key Shift_R; sleep 1
click 320 118          # Mods petal
click 173 55           # "All" (the menu remembers Visual from before)
xdotool mousemove --window "$WID" $(( 323 * 2 )) $(( 104 * 2 )); sleep 1.4
shot card-tooltip 0.2  # pointing at CPS for a moment shows its tip
click 369 76           # heart on CPS: a favourite
click 254 55           # Favourites
shot favourites 0.8
click 201 55           # On
shot on-filter 0.8
click 173 55           # All
rclick 213 104         # first card (CPS, the favourite) -> its settings, with the live preview
shot fps-settings 1
xdotool mousemove --window "$WID" 400 400; for i in 1 2 3 4 5 6; do xdotool click 5; sleep 0.15; done
shot fps-settings-look 0.8
click 135 328          # the Catgirl settings gear under your name
shot catgirl-settings 0.8
key Escape; key Escape; sleep 0.5   # settings -> mods -> game
# ---- add-ons that switch live (only when the test installed them)
if ls "$G/mods" | grep -qi continuity; then
  key Shift_R; sleep 1
  click 320 118; click 384 55   # Mods, Visual
  shot addons-ready 1
  click 543 258                 # Continuity on (a quick resource reload)
  sleep 8
  shot continuity-on 0.2
  key Escape
  cmd "setblock 2 -59 -3 glass"; cmd "setblock 3 -59 -3 glass"; cmd "setblock 2 -58 -3 glass"; cmd "setblock 3 -58 -3 glass"
  cmd "tp $P 2.5 -60 0.5 180 0"; sleep 3
  shot continuity-glass 0.5
  key Shift_R; sleep 1
  click 320 118; click 384 55
  click 433 258                 # Iris on (downloads a shader pack the first time)
  sleep 3
  shot iris-downloading 0.2
  key Escape
  sleep 60
  shot iris-shaders 2
fi
cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
cp "$G/options.txt" "$OUT/options-after.txt" 2>/dev/null
