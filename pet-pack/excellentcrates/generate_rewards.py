"""Generate the ExcellentCrates objects for the pet gacha.

Odds rule (owner asked for "sangat sulit"):
  items  = 11,000 weight  ->  ~99.46% of every roll
  pets   =     60 weight  ->  ~0.54% of every roll (1 pet per ~185 keys)
Only the 8 base pets (never a target of an evolution in pets.yml) are in the pool,
so an upgraded pet can never be rolled.
"""
import base64
import os
import re
import uuid

OUT = "gacha"
ITEMS = [
    # id, (type, sub-id, amount, weight, colour, name)
    ("pet_treat",              ("food", "small_treat", 10, 2600, "#CB883E", "Pet Treat")),
    ("pet_feast",              ("food", "large_feast", 5, 1500, "#FFF5A3", "Pet Feast")),
    ("pet_candy",              ("candy", "pet_candy", 4, 700, "#FFA96E", "Pet Candy")),
    ("super_candy",            ("candy", "super_candy", 1, 350, "#FFD460", "Super Pet Candy")),
    ("auto_feed_unlocker",     ("auto_feed_unlocker", "auto_feed_unlocker", 1, 250, "#FFD45C", "Auto-Feed Unlocker")),
    ("auto_feed_upgrade",      ("auto_feed_upgrade", "auto_feed_upgrade", 1, 800, "#FFB717", "Auto-Feed Upgrade")),
    ("revive_totem",           ("revive", "revive_totem", 1, 900, "#FFE135", "Pet Revival Totem")),
    ("evolution_stone",        ("evolution", "evolution_stone", 1, 700, "#31BCFF", "Evolution Stone")),
    ("blessed_evolution_stone", ("evolution", "blessed_evolution_stone", 1, 200, "#B184EF", "Blessed Evolution Stone")),
    ("reroll_token",           ("reroll", "reroll_token", 1, 800, "#88D7FD", "Stat Reroll Token")),
    ("blessed_reroll_token",   ("reroll", "blessed_reroll_token", 1, 200, "#70FFBA", "Blessed Stat Reroll Token")),
    ("stat_shard",             ("reroll", "stat_shard", 4, 700, "#DCA9FC", "Stat Shard")),
    ("stat_eraser",            ("reroll", "stat_eraser", 5, 500, "#FF8BC3", "Stat Eraser")),
    ("stat_lock_charm",        ("stat_lock", "stat_lock_charm", 5, 800, "#C0F18F", "Stat Lock Charm")),
]
ICON_MATERIAL = {
    "pet_treat": "cookie", "pet_feast": "pumpkin_pie", "pet_candy": "glow_berries",
    "super_candy": "golden_apple", "auto_feed_unlocker": "beetroot_soup", "auto_feed_upgrade": "golden_carrot",
    "revive_totem": "totem_of_undying", "evolution_stone": "amethyst_cluster",
    "blessed_evolution_stone": "firework_star", "reroll_token": "globe_banner_pattern",
    "blessed_reroll_token": "knowledge_book", "stat_shard": "large_amethyst_bud",
    "stat_eraser": "wind_charge", "stat_lock_charm": "name_tag",
}
# display tier per reward: the preview prints "Rarity: null" without a valid id.
# Rarity weight does NOT enter the roll (RewardEvaluationService.rollReward uses
# Reward.getWeight via WeightedRandomSelector; rarity only filters when a roll is
# explicitly asked for one rarity), so this is cosmetic and the odds stay as computed.
RARITY = {
    "pet_treat": "common", "pet_feast": "common",
    "pet_candy": "uncommon", "stat_eraser": "uncommon", "stat_shard": "uncommon",
    "auto_feed_upgrade": "uncommon",
    "auto_feed_unlocker": "rare", "revive_totem": "rare", "evolution_stone": "rare",
    "reroll_token": "rare",
    "super_candy": "epic", "blessed_evolution_stone": "epic", "blessed_reroll_token": "epic",
    "stat_lock_charm": "epic",
    "common_pet": "epic", "ember_pup": "epic", "coin_fox": "epic", "stone_golem": "epic",
    "lazy_sloth": "epic",
    "void_wisp": "legendary", "bloom_sprite": "legendary", "legendary_pet": "legendary",
}
# base pets only: roots of the evolution graph in live pets.yml
PETS = [
    ("common_pet", 20), ("ember_pup", 10), ("coin_fox", 10), ("stone_golem", 8),
    ("lazy_sloth", 6), ("void_wisp", 3), ("bloom_sprite", 2), ("legendary_pet", 1),
]
LEGACY = {"&l": "<bold>", "&f": "<white>", "&7": "<gray>", "&6": "<gold>", "&c": "<red>", "&e": "<yellow>"}


def yaml_str(text):
    return "'" + str(text).replace("'", "''") + "'"


def pet_data(pet_id):
    text = open("live_pets.yml", encoding="utf-8").read()
    block = re.search(r"^  %s:\n(.*?)(?=^  \S)" % pet_id, text, re.S | re.M).group(1)
    b64 = re.search(r"^\s+skull:\s*\"?([A-Za-z0-9+/=]+)", block, re.M).group(1)
    name = re.search(r"^\s+item-display-name:\s*\"?([^\"\n]+)", block, re.M).group(1).strip()
    return b64, name


def minimessage(legacy):
    out = legacy
    for code, tag in LEGACY.items():
        out = out.replace(code, tag)
    return "<gray>".join([out]) if not out.startswith("<") else out


def item_icon(material, name, colour, amount):
    """nightcore parses Data.Value as SNBT (Brigadier), not JSON - see assert_snbt."""
    return '{count:%d,id:"minecraft:%s"}' % (amount, material)


def head_icon(b64, name, colour):
    return ('{count:1,id:"minecraft:player_head",components:{"minecraft:profile":'
            '{properties:[{name:"textures",value:"%s"}]}}}' % b64)


def parse_snbt(text, pos=0):
    """Minimal SNBT parser (Brigadier grammar) so a JSON icon cannot slip through again."""
    while pos < len(text) and text[pos].isspace():
        pos += 1
    if pos >= len(text):
        raise ValueError("unexpected end")
    ch = text[pos]
    if ch == "{":
        pos += 1
        while True:
            while pos < len(text) and text[pos].isspace():
                pos += 1
            if text[pos] == "}":
                return pos + 1
            if text[pos] == '"':
                pos = _quoted(text, pos)
            else:
                key = re.match(r"[A-Za-z0-9_.+-]+", text[pos:])
                if not key:
                    raise ValueError("compound key must be bare [A-Za-z0-9_.+-] or quoted at %d: %r"
                                     % (pos, text[pos:pos + 12]))
                pos += key.end()
            while pos < len(text) and text[pos].isspace():
                pos += 1
            if text[pos] != ":":
                raise ValueError("expected ':' at %d: %r" % (pos, text[pos:pos + 12]))
            pos = parse_snbt(text, pos + 1)
            while pos < len(text) and text[pos].isspace():
                pos += 1
            if text[pos] == ",":
                pos += 1
            elif text[pos] != "}":
                raise ValueError("expected ',' or '}' at %d: %r" % (pos, text[pos:pos + 12]))
    elif ch == "[":
        pos += 1
        while True:
            while pos < len(text) and text[pos].isspace():
                pos += 1
            if text[pos] == "]":
                return pos + 1
            pos = parse_snbt(text, pos)
            while pos < len(text) and text[pos].isspace():
                pos += 1
            if text[pos] == ",":
                pos += 1
            elif text[pos] != "]":
                raise ValueError("expected ',' or ']' at %d" % pos)
    elif ch == '"':
        return _quoted(text, pos)
    else:
        token = re.match(r"[A-Za-z0-9_.+-]+[bslfd]?", text[pos:])
        if not token:
            raise ValueError("unexpected token at %d: %r" % (pos, text[pos:pos + 12]))
        return pos + token.end()


def _quoted(text, pos):
    pos += 1
    while pos < len(text):
        if text[pos] == "\\":
            pos += 2
            continue
        if text[pos] == '"':
            return pos + 1
        pos += 1
    raise ValueError("unterminated string")


def assert_snbt(value):
    """Guards the bug that broke the preview: Data.Value is SNBT (nightcore ItemTag), not JSON."""
    end = parse_snbt(value)
    assert value[end:].strip() == "", "trailing junk after the icon compound: %r" % value[end:]
    assert "{{" not in value, "opening double brace: Data.Value must be SNBT, not JSON"
    return value


REWARD = """base:
  weight: %(weight)s
preview:
  name: %(preview)s
  lore: []
  use_item_data: false
  icon:
    Data:
      Value: '%(icon)s'
      DataVersion: 5023
    Provider: vanilla
broadcast:
  enabled: %(broadcast)s
commands:
  enabled: true
  give_mode: NORMAL
  iterations: 1
  bundles:
    %(uuid)s:
      commands:
      - %(command)s
      weight: 0.0
items: {}
cooldown:
  global:
    enabled: false
    mode: CUSTOM
    value: 0
  individual:
    enabled: false
    mode: CUSTOM
    value: 0
limit:
  enabled: false
  global:
    Enabled: false
    Amount: 0
  individual:
    Enabled: false
    Amount: 0
  alternative:
    enabled: false
    reward_id:
      namespace: none
      value: none
Rarity:
  enabled: true
  rarity_id: %(rarity)s
"""


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


CRATE = """base:
  permission_required: false
display:
  name: <#A86BFF>Pet Gacha
  lore:
  - <gray>Base pets only &8- <white>never an upgraded one</white>
  - <gray>Pet chance: <white>1 in 185 keys</white>
item:
  item:
    Data:
      Value: '{count:1,id:"minecraft:purple_shulker_box"}'
      DataVersion: 5023
    Provider: vanilla
  item_stackable: false
  use_display: true
opening:
  enabled: true
  opening_commands: []
hologram:
  enabled: true
  text:
  - <#A86BFF><bold>%crate_name%</bold>
  - ''
  - '<gray>%crate_description%</gray>'
  - ''
  - <#83ca16>→ <#70e510>Left-Click <gray>to preview the crate</gray>
  - <#83ca16>→ <#70e510>Right-Click <gray>to open the crate</gray>
  offset:
    x: 0.0
    y: 0.7
    z: 0.0
cooldowns:
  global:
    enabled: false
    mode: DAILY
    value: 0
  individual:
    enabled: false
    mode: DAILY
    value: 0
animation:
  enabled: true
  profile: csgo_animation:default
effect:
  enabled: true
  profile: vortex:default
key_requirements:
  enabled: true
  keys:
    pet_gacha:
      amount: 1
preview:
  enabled: true
  config: inventory:default
rewards:
  required_rewards: 1
  reward_list:
@@REWARDS@@
selective_rewards:
  enabled: false
blocks:
  positions: {}
"""

KEY = """base:
  virtual: false
display:
  name: <#A86BFF>Pet Gacha Key
  lore:
  - <gray>Opens the Pet Gacha crate.</gray>
  - ''
  - '<gray>Pets in the pool: <white>8 base pets only</white>'
  - '<gray>Upgraded pets are never rolled.'
item:
  item:
    Data:
      Value: '{count:1,id:"minecraft:trial_key"}'
      DataVersion: 5023
    Provider: vanilla
  stackable: true
  inherit_display_settings: true
"""


def write_crate(reward_ids):
    lines = []
    for rid in reward_ids:
        lines.append("    %s:" % uuid.uuid4())
        lines.append("      reward_id: %s" % rid)
    write(OUT + "/objects/crate/pet_gacha.yml", CRATE.replace("@@REWARDS@@", "\n".join(lines)))
    write(OUT + "/objects/key/pet_gacha.yml", KEY)


def main():
    reward_ids, roll_weights = [], []

    for rid, (ptype, sub, amount, weight, colour, name) in ITEMS:
        reward_ids.append(rid)
        roll_weights.append((rid, weight))
        write("%s/objects/reward/pet_gacha/%s.yml" % (OUT, rid), REWARD % {
            "weight": float(weight),
            "preview": yaml_str("<%s>%s x%d" % (colour, name, amount)),
            "broadcast": "false",
            "uuid": uuid.uuid4(),
            "command": yaml_str("slimefunpet givepetitem %%player_name%% %s %s %d" % (ptype, sub, amount)),
            "icon": assert_snbt(item_icon(ICON_MATERIAL[rid], name, colour, amount)),
            "rarity": RARITY[rid],
        })

    for pet_id, weight in PETS:
        b64, legacy = pet_data(pet_id)
        tier = re.match(r"&.\s*(&#..|&.)?&l(\[[IVX]+\])\s*(&#..|&.)?(.*)", legacy.replace("&l", "&l ", 1))
        pretty = re.sub(r"&#[0-9A-Fa-f]{6}", "", legacy)
        reward_ids.append(pet_id)
        roll_weights.append((pet_id, weight))
        write("%s/objects/reward/pet_gacha/%s.yml" % (OUT, pet_id), REWARD % {
            "weight": float(weight),
            "preview": yaml_str("<#FFD27A><bold>%s</bold> <white>Base pet</white>" % re.sub(r"&.", "", legacy).strip()),
            "broadcast": "true" if pet_id == "legendary_pet" else "false",
            "uuid": uuid.uuid4(),
            "command": yaml_str("slimefunpet givepet %%player_name%% %s 1" % pet_id),
            "icon": assert_snbt(head_icon(b64, re.sub(r"&.", "", legacy).strip(), None)),
            "rarity": RARITY[pet_id],
        })

    total = sum(w for _, w in roll_weights)
    pets_total = sum(w for rid, w in roll_weights if rid in dict(PETS))
    print("rewards: %d (items %d, pets %d)" % (len(roll_weights), len(roll_weights) - len(PETS), len(PETS)))
    print("weight total %d | pet weight %d -> %.3f%% chance of a pet per key" % (total, pets_total, 100.0 * pets_total / total))
    for rid, weight in roll_weights:
        print("   %-24s %6.1f  %6.3f%%" % (rid, weight, 100.0 * weight / total))
    write(OUT + "/reward_ids.txt", "\n".join(reward_ids) + "\n")
    write_crate(reward_ids)


if __name__ == "__main__":
    main()
