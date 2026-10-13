# The shots, run by shots.sh (cmd = server console command, key = press a key, shot = screenshot).
P=CatTester
G1='[enchantment_glint_override=true]'
cmd "gamerule doDaylightCycle false"; cmd "gamerule doWeatherCycle false"; cmd "time set 6000"; cmd "weather clear"
cmd "gamerule sendCommandFeedback false"; cmd "gamerule doImmediateRespawn true"
# (1.21.11 renamed some game rules; try both spellings)
cmd "gamerule advance_time false"; cmd "gamerule advance_weather false"; cmd "gamerule send_command_feedback false"
cmd "tp $P 0.5 -60 0.5 180 10"
cmd "clear $P"
cmd "item replace entity $P hotbar.0 with diamond_sword$G1"
cmd "item replace entity $P hotbar.1 with netherite_sword"
cmd "item replace entity $P hotbar.2 with golden_sword"
cmd "item replace entity $P hotbar.3 with diamond_axe"
cmd "item replace entity $P hotbar.4 with iron_axe"
cmd "item replace entity $P hotbar.5 with mace"
cmd "item replace entity $P hotbar.6 with totem_of_undying"
cmd "item replace entity $P hotbar.7 with copper_sword"
cmd "item replace entity $P hotbar.8 with wooden_sword"
cmd "item replace entity $P weapon.offhand with totem_of_undying"
shot hand 3
cmd "item replace entity $P hotbar.0 with netherite_sword$G1"
shot hand-netherite 2
# effects in front of the camera, one kind at a time, kept coming while the picture is taken
burst() { for i in $(seq 1 30); do echo "execute as $P at @s anchored eyes run particle $1" >&3; sleep 0.15; done; }
burst "minecraft:sweep_attack ^ ^ ^2.5 0 0 0 0 1 force" & B=$!; shot sweep 1.2; wait $B
burst "minecraft:crit ^ ^ ^3 0.4 0.3 0.4 0 6 force" & B=$!; shot crit 1.2; wait $B
burst "minecraft:enchanted_hit ^ ^ ^3 0.4 0.3 0.4 0 6 force" & B=$!; shot enchanted-hit 1.2; wait $B
burst "minecraft:totem_of_undying ^ ^ ^3 0.6 0.4 0.6 0 12 force" & B=$!; shot totem-particles 1.2; wait $B
burst "minecraft:damage_indicator ^ ^ ^3 0.4 0.3 0.4 0 4 force" & B=$!; shot damage-hearts 1.2; wait $B
# a real totem pop (survival, then enough damage to die)
cmd "gamemode survival $P"
cmd "effect give $P minecraft:instant_health 1 5"
sleep 1
cmd "damage $P 100 minecraft:generic"
shot totem-pop-a 0.2
shot totem-pop-b 0.1
cmd "gamemode creative $P"
cmd "effect clear $P"
cmd "item replace entity $P weapon.offhand with totem_of_undying"
cmd "item replace entity $P hotbar.0 with diamond_sword$G1"
# third person: cosmetics with no armour
key F1
key F5
xdotool keydown c   # hold zoom for the close-ups (tests the zoom too)
cmd "tp $P 0.5 -60 0.5 180 0"
shot back 3
key F5
shot front 3
# armour (glint on), front then back
for m in diamond netherite golden iron; do
  cmd "item replace entity $P armor.head with ${m}_helmet$G1"
  cmd "item replace entity $P armor.chest with ${m}_chestplate$G1"
  cmd "item replace entity $P armor.legs with ${m}_leggings$G1"
  cmd "item replace entity $P armor.feet with ${m}_boots$G1"
  shot front-$m 3
done
key F5; key F5
shot back-iron 3
cmd "item replace entity $P armor.head with chainmail_helmet"
cmd "item replace entity $P armor.chest with copper_chestplate"
cmd "item replace entity $P armor.legs with chainmail_leggings"
cmd "item replace entity $P armor.feet with copper_boots"
key F5
shot front-chain-copper 3
cmd "item replace entity $P weapon.mainhand with totem_of_undying"
shot front-totem-both-hands 3
xdotool keyup c

# ---------------------------------------------------------------- Catgirl menu (Right Shift)
# GUI scale 2 on a 1280x720 window = 640x360 GUI pixels; click() takes GUI pixels.
click() { xdotool mousemove --window "$WID" $(( $1 * 2 )) $(( $2 * 2 )); sleep 0.3; xdotool click 1; sleep 0.5; }
key F5          # back to first person
key F1          # show the HUD again
cmd "item replace entity $P armor.head with diamond_helmet"
cmd "item replace entity $P armor.chest with diamond_chestplate"
cmd "item replace entity $P weapon.mainhand with diamond_sword"
key Shift_R
shot bloom 1.2
click 320 118   # the Mods petal
shot panel-mods 1
# panel: px=30 py=15 (580x330), sidebar 118; cards 104x71 in 4 columns from (161, 69), 110/77 apart
for i in 0 1 2 3 4 5 6; do click $(( 213 + (i % 4) * 110 )) $(( 104 + (i / 4) * 77 )); done
shot panel-mods-on 1
xdotool type --delay 80 apple
shot panel-search 1
click 213 104   # Food Info, the only card left
sleep 8
shot panel-addon 1
ls -la "$G/mods" > "$OUT/mods-after.txt"
for k in 1 2 3 4 5; do xdotool key BackSpace; sleep 0.1; done
click 311 55    # HUD category
shot panel-hud-category 1
click 90 115    # Cosmetics in the sidebar
shot panel-cosmetics 1.5
click 90 59     # Edit HUD in the sidebar
shot hud-editor 1.5
xdotool mousemove --window "$WID" 20 20; sleep 0.3; xdotool mousedown 1; sleep 0.2
for s in 1 2 3 4 5 6 7 8 9 10; do xdotool mousemove --window "$WID" $(( 20 + s * 30 )) $(( 20 + s * 20 )); sleep 0.05; done
xdotool mouseup 1; sleep 0.5
shot hud-editor-dragged 1
xdotool mousemove --window "$WID" 330 220; sleep 0.2; xdotool click 4; sleep 0.2; xdotool click 4; sleep 0.2; xdotool click 4
shot hud-editor-bigger 1
key Escape      # back to the panel
key Shift_R     # and close it
xdotool keydown w; xdotool click --repeat 8 --delay 70 1; xdotool click --repeat 3 --delay 70 3
shot hud-in-game 0.1
xdotool keyup w
xdotool keydown c; sleep 1.2
shot zoom 0.3
xdotool keyup c; sleep 1
shot after-zoom 1
cp "$G/config/catgirl-hud.json" "$OUT/catgirl-hud.json" 2>/dev/null
cp "$G/config/catgirl-cosmetics-edit.json" "$OUT/catgirl-cosmetics-edit.json" 2>/dev/null
cp "$G/options.txt" "$OUT/options-after.txt" 2>/dev/null
