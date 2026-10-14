# Test of the World mods: Sky styles (day, sunset, night), Ambience (petals by day, fireflies at
# night), Mist, Soft Focus (depth of field and dreamy edges), the Landing Ring and Afterglow,
# plus the World tab and the Sky settings page with its style strips.
P=CatTester
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
rclick() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 3; sleep 0.5; }
# several screenshots in quick succession (for things that only last a moment)
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
# open the Catgirl menu on the Mods page and search for a mod (the first card is then that mod)
find_mod() { key Shift_R; sleep 1; click 320 118; sleep 0.5; xdotool type --delay 80 "$1"; sleep 0.8; }

cmd "gamerule doDaylightCycle false"; cmd "gamerule advance_time false"; cmd "weather clear"
cmd "gamemode creative $P"; cmd "clear $P"; cmd "effect clear $P"
# a little scene: pillars at different distances (for Soft Focus), a pond, flowers
cmd "fill -2 -60 -5 -2 -57 -5 pink_wool"
cmd "fill 3 -60 -13 3 -55 -13 light_blue_wool"
cmd "fill -5 -60 -26 -5 -51 -26 purple_wool"
cmd "fill 7 -60 -44 7 -49 -44 white_wool"
cmd "fill -8 -61 -12 -4 -61 -8 water"
cmd "setblock 1 -60 -3 pink_tulip"; cmd "setblock -1 -60 -2 allium"; cmd "setblock 2 -60 -6 oxeye_daisy"
cmd "time set 6000"
cmd "tp $P 0.5 -60 0.5 180 -6"
sleep 4
shot sky-sakura-day 3
cmd "time set 12650"
cmd "tp $P 0.5 -60 0.5 90 -4"
shot sky-sakura-sunset 4

# ---- the World tab and the Sky settings page
key Shift_R; sleep 1
click 320 118          # Mods petal
click 428 55           # World
shot world-tab 1
rclick 213 104         # first card: Sky -> its settings
shot sky-settings 1
click 590 81           # Style: next (Starry Night)
shot sky-settings-starry 0.8
key Escape; key Escape; sleep 0.5

# ---- Starry Night, and fireflies (Ambience on Auto)
cmd "time set 18000"
cmd "tp $P 0.5 -60 0.5 160 -38"
shot sky-starry-night 5
cmd "tp $P 0.5 -60 0.5 200 -30"
sleep 1
burst shooting-stars 6 0.6
cmd "tp $P 0.5 -60 0.5 180 8"
shot fireflies 4

# ---- Mist (switched on from the menu), morning light
find_mod mist
click 213 104          # Mist on
shot mist-card 0.8
key Escape; key Escape; sleep 0.5
cmd "time set 1000"
cmd "tp $P 0.5 -60 0.5 180 -2"
shot mist-morning 4
find_mod mist
click 213 104          # Mist off again
key Escape; key Escape; sleep 0.5

# ---- Soft Focus: look at the pink pillar 5 blocks away; the far ones go soft
cmd "time set 6000"
find_mod focus
click 213 104          # Soft Focus on
key Escape; key Escape; sleep 0.5
cmd "tp $P -1.5 -60 0.5 180 7"
shot softfocus-depth 3
find_mod focus
rclick 213 104         # its settings
click 590 81           # Focus: dreamy edges
key Escape; key Escape; key Escape; sleep 0.5
shot softfocus-edges 3
find_mod focus
click 213 104          # Soft Focus off
key Escape; key Escape; sleep 0.5

# ---- Landing Ring: drop from 10 blocks up
cmd "tp $P 0.5 -50 0.5 180 50"
sleep 0.9
burst landing 7 0.12

# ---- Afterglow: a pig gets... sent to a farm upstate
cmd "tp $P 0.5 -60 0.5 180 20"
cmd "summon pig 0.5 -60 -3 {NoAI:1b,Silent:1b}"
sleep 1.5
cmd "kill @e[type=pig]"
sleep 0.05
burst afterglow 4 0.2

cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
grep -iE 'post|shader|soft_focus|catgirl' "$G/logs/latest.log" | grep -iE 'error|fail|warn|exception' | head -40 > "$OUT/visuals-errors.txt"
