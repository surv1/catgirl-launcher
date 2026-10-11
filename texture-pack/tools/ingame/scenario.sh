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
# effects in front of the camera
for i in 1 2 3; do
  cmd "execute as $P at @s anchored eyes run particle minecraft:sweep_attack ^-0.6 ^ ^2.2 0 0 0 0 1 force"
  cmd "execute as $P at @s anchored eyes run particle minecraft:crit ^0.6 ^ ^2.5 0.3 0.3 0.3 0.3 25 force"
  cmd "execute as $P at @s anchored eyes run particle minecraft:enchanted_hit ^0 ^0.5 ^2.5 0.3 0.3 0.3 0.3 25 force"
done
shot hit-effects 0.1
cmd "execute as $P at @s anchored eyes run particle minecraft:totem_of_undying ^ ^ ^2.5 0.6 0.6 0.6 0.4 200 force"
shot totem-particles 0.3
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
cmd "tp $P 0.5 -60 0.5 180 0"
shot back 3
key F5
shot front 3
# armour (glint on), front then back
for m in diamond netherite gold iron; do
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
