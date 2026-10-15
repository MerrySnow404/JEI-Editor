"""Generate docs/JEI_PAGE_COMPAT_TODO.md from the runtime category dump.

Run from the repository root:
    python build/tmp/modsearch/gen_checklist.py
"""
import glob
import io
import os
import re
from collections import defaultdict, OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DUMP = os.path.join(ROOT, "targets", "neoforge-1.21.1", "run", "jei-category-dump.txt")
OUT = os.path.join(ROOT, "docs", "JEI_PAGE_COMPAT_TODO.md")

MOD_NAMES = OrderedDict([
    ("minecraft", "Minecraft / JEI 本体"),
    ("jei", "Minecraft / JEI 本体"),
    ("create", "Create"),
    ("immersiveengineering", "Immersive Engineering"),
    ("mekanism", "Mekanism"),
    ("silentgear", "Silent Gear"),
    ("silentgearjei", "SilentGearJEI"),
    ("jearchaeology", "Just Enough Archaeology"),
    ("horsepowered", "Horse Powered"),
    ("cobblemon", "Cobblemon"),
    ("betterpiglintrades", "Better Piglin Trades"),
    ("rackitup", "Rack It Up"),
    ("createresourcegeodes", "Resource Geodes & Catalysts"),
    ("nnp_easy_farming", "NNP Easy Farming"),
    ("smithingtemplateviewer", "Smithing Template Viewer"),
    ("just_enough_beacons", "Just Enough Beacons Reforged"),
    ("ali", "Advanced Loot Info"),
    ("awi", "Advanced Worldgen Info"),
    ("cobbledex-rei-emi-jei", "Cobblemon Info (CobbleDex)"),
    ("jeresources", "Just Enough Resources"),
    ("justenoughbreeding", "Just Enough Breeding"),
    ("justenoughprofessions", "Just Enough Professions"),
    ("jei_mekanism_multiblocks", "Just Enough Mekanism Multiblocks"),
    ("jeimultiblocks", "Just Enough Immersive Multiblocks"),
    ("jeiworldgen", "JEI WorldGen"),
    ("jerm", "JERM"),
    ("jea", "Just Enough Advancements"),
    ("jeed", "JEED"),
    ("uei", "Universal Enchantment Info"),
    ("jefa", "JEFA"),
])

# Namespaces whose pages are not recipe pages at all (loot tables, worldgen,
# bestiary, statistics) even though some are wrapped in a RecipeHolder.
INFO_NAMESPACES = {
    "ali", "awi", "cobbledex-rei-emi-jei", "jeresources", "justenoughbreeding",
    "justenoughprofessions", "jei_mekanism_multiblocks", "jeimultiblocks",
    "jeiworldgen", "jerm", "jea", "jeed", "uei", "silentgearjei", "jefa",
}

VANILLA_SUPPORTED = {
    "minecraft:crafting", "minecraft:smelting", "minecraft:blasting",
    "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:stonecutting",
    "minecraft:smithing", "minecraft:anvil", "minecraft:brewing",
    "minecraft:grindstone", "minecraft:compostable", "minecraft:fuel",
    "jei:information",
}


def implemented_vanilla_serializers():
    """The vanilla serializers the write path implements itself.

    Read from ``RecipeEditorAdapters.isImplementedVanillaSerializer``'s three
    adapters so the list cannot drift. A recipe whose serializer is one of these
    is patched in the vanilla JSON shape by its own adapter - no declaration is
    involved - which is what a page that *displays* such a recipe needs.
    """
    pattern = os.path.join(ROOT, "targets", "neoforge-1.21.1", "src", "main", "java",
                           "cc", "sighs", "JEIEditor", "platform", "recipe",
                           "*RecipeEditorAdapter.java")
    found = set()
    for path in glob.glob(pattern):
        with open(path, encoding="utf-8") as handle:
            source = handle.read()
        body = re.search(r"public static boolean supportsSerializer\(String serializerId\) \{(.*?)\n    \}",
                         source, re.S)
        if body:
            found.update(re.findall(r'"(minecraft:[a-z_]+)"', body.group(1)))
    return found


IMPLEMENTED_VANILLA = implemented_vanilla_serializers()

# Pages a declaration cannot cover: their recipes never enter the recipe manager (or
# share a declared serializer whose recipes are generated at reload time), so nothing
# on them is patchable and the client builds no model. Proven by
# scripts/jei-client-tests.ps1, whose gate reports 0 models for them.
NOT_COVERED_UIDS = {
    # Shares immersiveengineering:bottling_machine, but its recipes are synthesised for
    # display (ids like immersiveengineering:arc_*_list000) with no recipe JSON.
    "immersiveengineering:arc_recycling",
    # Silent Gear's fabric and super alloys are generated from its material data and
    # never enter the manager at all - not even the client can resolve a holder for
    # them - so neither page has a recipe to patch.
    "silentgear:alloy_making/fabric",
    "silentgear:alloy_making/super",
    # The page's displayed object is MaterialGraderRecipeCategory$GraderRecipe, a helper
    # record built inside the category to list the gradable items (one "recipe" with 173
    # candidates). It is not a recipe the manager holds, so it has no id and no holder:
    # the client can model nothing.
    "silentgear:grading",
    # Create's mysterious-item-conversion page displays
    # com.simibubi.create.compat.jei.ConversionRecipe objects - a JEI-compat display class
    # (it lives in the compat package, not in Create's recipe registry) whose ids are
    # synthesised (create:conversion_0, _1) and have no recipe JSON. Nothing on the page
    # resolves to a manager recipe, so the client builds no model.
    "create:mystery_conversion",
    # JEI's own tag pages used to be listed here. They are wired now (see
    # TAG_PAGE_UIDS): a tag page is modelled as one slot holding the tag's first
    # member and edited through the tag patch kind, so it is a `done` row rather
    # than a gap. Nothing of theirs is left in this set.
    # --- recipes that exist only in code, so there is no recipe JSON to patch ---
    # Just Enough Archaeology builds its archaeology and sniffing recipes in code from
    # loot tables: the jar ships no data JSON at all (assets and classes only), so the
    # displayed ids (jearchaeology:archaeology/ocean_ruin_warm, jearchaeology:sniffing)
    # have no file behind them.
    "jearchaeology:brush",
    "jearchaeology:sniff",
    # --- pages whose displayed object is a JEI-integration class, not a manager recipe ---
    # Each of these carries its own recipe class in a "compat"/"integration"/client-viewer
    # package, is absent from the dump's recipe-id column and builds no model. Cobblemon's
    # berry mutation, Resource Geodes' catalyst recipes, Better Piglin Trades' barter
    # entries, the three Just Enough Beacons pages, Mekanism's two multiblock preview
    # pages and the Smithing Template Viewer's trim wrapper are all of that kind.
    "cobblemon:berry_recipe",
    "createresourcegeodes:catalyst_generation",
    "betterpiglintrades:piglin_barter",
    "just_enough_beacons:beacon_base_block",
    "just_enough_beacons:beacon_payment",
    "just_enough_beacons:conduit_frame_block",
    "mekanism:boiler_casing",
    "mekanism:sps_casing",
    "smithingtemplateviewer:smithing_trim",
    # --- worldgen/info pages that wear a RecipeHolder of their own ---
    # The dimensions and NNP Easy Farming's farming stems come from Advanced Worldgen
    # Info, whose com.yanny.awi...RecipeHolder is that mod's own holder class - it makes
    # the dump's class column look like a recipe, but the pages list biomes and crop
    # placements, not recipes.
    "minecraft:overworld",
    "minecraft:the_nether",
    "minecraft:the_end",
    "nnp_easy_farming:farming_stem",
    # --- real mod recipes whose slots no declaration can name ---
    # Reasons next to their declarations in MiscRecipeDeclarations/MekanismRecipeDeclarations:
    # Horse Powered's bottling output carries a component patch (a written result would drop
    # its potion contents) and its trapping output is a spawn egg derived from an "entity"
    # string (no item field to write); Mekanism's nutritional liquifier is its client-only
    # recipe-viewer recipe, which has no serializer the write path could address.
    "horsepowered:bottling",
    "horsepowered:trapping",
    "mekanism:nutritional_liquifier",
    # Create's item-drain page cannot be declared with the mechanism the project has: 78 of
    # its 80 entries are ItemDrainCategory#consumeRecipes entries, and those draw an input
    # slot holding a component-carrying stack (a Mekanism creative fluid tank) plus an empty
    # item output. A derived declaration has to claim the page's slots by kind, and the
    # client test's derived-shape rule rejects an input slot that shows nothing addressable
    # (measured: 57 in-client failures, e.g. "draws 1 input slots but its item band (0) and
    # fluid band (0) hold 0" and "leaves the item/fluid slot output.1 empty" on
    # create:empty_mekanism_creative_fluid_tank_of_mekanism_brine). The page's own two
    # reachable entries would be editable, but no declaration can be asserted for the page
    # as a whole, so it stays unmodelled and is recorded here instead.
    "create:draining",
}

# Pages whose displayed holder wraps a vanilla recipe: Create shows its
# "automatic packing / shapeless" pages as BasinRecipeHolders (the dump's serializer
# column says create:basin), while the id that holder carries resolves to the recipe the
# manager really holds - a minecraft:crafting_shaped / crafting_shapeless one that the
# write path implements itself. That is the same shape mekanism:smelting's alias has
# (see ALIASED_UIDS), just without an id rewrite, so no static rule can see it: the ids
# are ordinary ones and the wrapper is Create's.
# Both pages are asserted by scripts/jei-client-tests.ps1 (each must build at least one
# model: 40/40 and 24/40 sampled - the rest of automatic_shapeless carries serializers
# the editor does not implement, e.g. refinedstorage:recoloring) and the write behind
# them by scripts/jei-declared-smoke.ps1's create:automatic_packing probe, which patches
# minecraft:bone_block and asserts the written minecraft:crafting_shaped JSON.
RESOLVED_VANILLA_UIDS = {
    "create:automatic_packing",
    "create:automatic_shapeless",
}

# JEI's own tag pages, which are `done` without a ModdedRecipeDeclaration for the same
# reason the two pages above are: what they display is not a recipe record at all, so
# there is no declaration to write. Each page lists the members of one item, block or
# fluid tag (JEI registers one such category per registry and names the uid
# `<registry namespace>:tag_recipes/<registry path>`), and the editor edits the tag
# itself: JeiRecipeEditorPlugin.createTagModel() models the page as one slot holding the
# tag's first member - a tag can hold 1052 members (minecraft:item) while EditorModel
# accepts at most 128 slots, and every operation names one member - and every operation
# travels as a tag patch (serializer `jeieditor:tag`, ops member.add / member.remove /
# tag.create / tag.delete) that the server's TagEditApplier rewrites the tag from.
# scripts/jei-client-tests.ps1 asserts the pages through the tag branch of its own
# checks (one slot, a drop that becomes a member-add of the dropped ingredient - on the
# block page the block id that item places - and the three menu operations), and the
# write path by scripts/jei-declared-smoke.ps1's seven tag probes.
#
# This list is the reversal of an earlier decision: the three uids used to sit in
# NOT_COVERED_UIDS and in the client test script's locked known-unsupported list, both
# of which required them to produce zero models. That was true while no editor path
# accepted a bare TagInfoRecipe; it is not any more.
TAG_PAGE_UIDS = {
    "minecraft:tag_recipes/item",
    "minecraft:tag_recipes/block",
    "minecraft:tag_recipes/fluid",
}


def read_only_page_uids():
    """Page uids a declaration names as read-only (``withReadOnlyPages``).

    Read straight from the declaration files so this list cannot drift: such a
    page shares a declared serializer and often the same slot shape, but its
    recipes are generated at reload time and have no recipe JSON to patch, so
    the editor builds no model for it and the checklist has to say ``gap``.
    """
    pattern = os.path.join(ROOT, "targets", "neoforge-1.21.1", "src", "main", "java",
                           "cc", "sighs", "JEIEditor", "platform", "recipe",
                           "*RecipeDeclarations.java")
    found = set()
    for path in glob.glob(pattern):
        with open(path, encoding="utf-8") as handle:
            for match in re.finditer(r'withReadOnlyPages\(([^)]*)\)', handle.read()):
                found.update(re.findall(r'"([^"]+)"', match.group(1)))
    return found


READ_ONLY_UIDS = read_only_page_uids()


def aliased_page_uids():
    """Page uids whose displayed recipe ids are rewritten versions of a real id.

    ``RecipeViewerAliases`` is the source of truth: such a page draws recipes the
    manager holds, under an id that resolves nowhere, and the editor resolves the
    alias back to the real recipe and patches that one through its own
    serializer. Read from the source so this list cannot drift.
    """
    path = os.path.join(ROOT, "targets", "neoforge-1.21.1", "src", "main", "java",
                        "cc", "sighs", "JEIEditor", "platform", "recipe",
                        "RecipeViewerAliases.java")
    if not os.path.exists(path):
        return set()
    with open(path, encoding="utf-8") as handle:
        return set(re.findall(r'prefixes\.put\("([^"]+)"', handle.read()))


ALIASED_UIDS = aliased_page_uids()


def load():
    rows = []
    with open(DUMP, encoding="utf-8") as handle:
        handle.readline()
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            parts += [""] * (9 - len(parts))
            rows.append(dict(zip(
                ["uid", "visible", "recipes", "cat", "rcls", "jar", "title", "shape", "sample"], parts)))
    return rows


def slots(shape):
    ins = len(re.findall(r"input\[", shape))
    outs = len(re.findall(r"output\[", shape))
    return ins, outs


def non_item(shape):
    return bool(re.search(r"(?:input|output)\[\d+\]=(?!item_stack)", shape))


def serializer(row):
    match = re.search(r"type=(\S+)", row["sample"])
    return match.group(1) if match else ""


def is_holder(row):
    return "RecipeHolder" in row["rcls"]


def namespace(uid):
    return uid.split(":", 1)[0]


def declared_serializers():
    """Serializers that already have a declaration entry in the target sources.

    Read straight from the declaration files so this list cannot drift.
    """
    pattern = os.path.join(ROOT, "targets", "neoforge-1.21.1", "src", "main", "java",
                           "cc", "sighs", "JEIEditor", "platform", "recipe",
                           "*RecipeDeclarations.java")
    found = set()
    for path in glob.glob(pattern):
        with open(path, encoding="utf-8") as handle:
            for match in re.finditer(r'ModdedRecipeDeclaration\(\s*"([^"]+)"', handle.read()):
                found.add(match.group(1))
    return found


DECLARED = declared_serializers()

# Pages whose displayed object is not a RecipeHolder, so the dump has no
# serializer for them. The authoritative uid -> serializer mapping is the
# comment above each entry in platform/recipe/MiscRecipeDeclarations.java
# (and SilentGearRecipeDeclarations.java for the silentgear ones, whose pages
# push bare recipe objects into JEI); keep this list in step with it.
DECLARED_UIDS = {
    "horsepowered:chopping",
    "horsepowered:pressing",
    "horsepowered:manual_chopping",
    "horsepowered:crushing",
    "horsepowered:drying",
    "horsepowered:grinding",
    "horsepowered:manual_grinding",
    "rackitup:drying",
    "nnp_easy_farming:grindstone_recipe",
    "silentgear:alloy_making/metal",
    "silentgear:alloy_making/gem",
    "silentgear:gear_crafting",
    "silentgear:salvaging",
    "silentgear:tool_action",
    "cobblemon:campfire_pot_recipe",
}


def status_of(row):
    if row["uid"] in NOT_COVERED_UIDS or row["uid"] in READ_ONLY_UIDS:
        return "gap"
    if (row["uid"] in VANILLA_SUPPORTED or row["uid"] in VANILLA_BACKED
            or row["uid"] in RESOLVED_VANILLA_UIDS or row["uid"] in TAG_PAGE_UIDS):
        return "done"
    if (serializer(row) in DECLARED or row["uid"] in DECLARED_UIDS
            or row["uid"] in ALIASED_UIDS):
        return "declared"
    return "todo"


def vanilla_backed_uids(rows):
    """Page uids that display recipes the vanilla adapters already patch.

    Create's ``create:fan_smoking`` page draws the ``minecraft:smoking`` recipes
    the recipe manager holds, under their own ids and with their own serializer
    (the dump's ``type=`` column names it), so the editor models them through
    ``CookingRecipeEditorAdapter`` and the patch carries ``minecraft:smoking`` -
    the same path ``minecraft:smoking`` itself uses. No declaration is involved,
    and none may be added: a serializer-keyed declaration would take the page away
    from the vanilla adapter. The checklist therefore counts such a page as done
    on the strength of the serializer its displayed recipe carries, which is the
    same fact ``scripts/jei-client-tests.ps1`` asserts by requiring the page to
    build at least one model.
    """
    backed = {}
    for row in rows:
        current = serializer(row)
        if current in IMPLEMENTED_VANILLA and row["uid"] not in VANILLA_SUPPORTED:
            backed[row["uid"]] = current
    return backed


VANILLA_BACKED = {}


def render_table(out, rows, with_status, with_uid_status=False):
    if with_status:
        out.write("| 页面 uid | 配方数 | JEI 原料槽（入/出） | serializer | 状态 |\n")
        out.write("| --- | --- | --- | --- | --- |\n")
    elif with_uid_status:
        out.write("| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 | 状态 |\n")
        out.write("| --- | --- | --- | --- | --- |\n")
    else:
        out.write("| 页面 uid | 配方数 | JEI 原料槽（入/出） | 说明 |\n")
        out.write("| --- | --- | --- | --- |\n")
    for row in rows:
        ins, outs = slots(row["shape"])
        if with_status:
            out.write("| `%s` | %s | %d / %d | `%s` | %s |\n"
                      % (row["uid"], row["recipes"], ins, outs, serializer(row) or "—",
                         status_of(row)))
        elif with_uid_status:
            out.write("| `%s` | %s | %d / %d | %s | %s |\n"
                      % (row["uid"], row["recipes"], ins, outs, row["title"][:24],
                         status_of(row)))
        else:
            out.write("| `%s` | %s | %d / %d | %s |\n"
                      % (row["uid"], row["recipes"], ins, outs, row["title"][:24]))


def group_by_namespace(out, rows, with_status, with_uid_status=False):
    buckets = defaultdict(list)
    for row in rows:
        buckets[namespace(row["uid"])].append(row)
    for ns in sorted(buckets):
        out.write("\n**%s**（`%s`）\n\n" % (MOD_NAMES.get(ns, ns), ns))
        render_table(out, sorted(buckets[ns], key=lambda r: r["uid"]), with_status, with_uid_status)


def main():
    rows = load()
    global VANILLA_BACKED
    VANILLA_BACKED = vanilla_backed_uids(rows)
    supported = [r for r in rows if r["uid"] in VANILLA_SUPPORTED]
    rest = [r for r in rows if r["uid"] not in VANILLA_SUPPORTED]
    info = [r for r in rest if namespace(r["uid"]) in INFO_NAMESPACES]
    recipeish = [r for r in rest if namespace(r["uid"]) not in INFO_NAMESPACES]
    plain = [r for r in recipeish if is_holder(r) and not non_item(r["shape"])]
    fluidic = [r for r in recipeish if is_holder(r) and non_item(r["shape"])]
    synthetic = [r for r in recipeish if not is_holder(r)]

    out = io.StringIO()
    out.write("# 1.21.1 NeoForge JEI 页面兼容清单（TODO）\n\n")
    out.write("本清单列出开发环境里 JEI 实际注册的**全部配方页面**，并按能否可视化编辑分组，"
              "作为逐页接入的待办表。单页接入流程见 "
              "[JEI_PAGE_COMPAT_WORKFLOW.md](JEI_PAGE_COMPAT_WORKFLOW.md)，"
              "已支持的 vanilla 页面见 [SUPPORTED_RECIPES.md](SUPPORTED_RECIPES.md)。\n\n")
    out.write("- 目标环境：Minecraft 1.21.1 / NeoForge 21.1.238 / JEI 19.44.0.403\n")
    out.write("- 模组环境：`targets/neoforge-1.21.1/run/mods/` 下 68 个启用 jar（清单见 "
              "[JEI_ADDONS_1.21.1_NEOFORGE.md](JEI_ADDONS_1.21.1_NEOFORGE.md) 与 "
              "[JEI_ADDONS_CONTENT_MODS.md](JEI_ADDONS_CONTENT_MODS.md)）\n")
    out.write("- 数据来源：开发客户端实际启动并进世界后，由临时诊断导出 `run/jei-category-dump.txt`"
              "（%d 个页面）。静态扫描脚本 `scan_categories.py` 作为交叉验证，"
              "只能看到显式 `RecipeType.create` 调用，因此列表以运行时为准。\n" % len(rows))
    out.write("- `run/` 被 `.gitignore` 忽略，导出文件与两个扫描脚本都不入库。\n\n")

    out.write("## 分类总览\n\n")
    out.write("| 组 | 页面数 | 判据 | 能否可视化编辑 | 处理方式 |\n")
    out.write("| --- | --- | --- | --- | --- |\n")
    out.write("| 0 已支持 | %d | vanilla serializer，编辑器已有适配器 | 能，已实现 | 无 |\n" % len(supported))
    out.write("| A1 可直接接入 | %d | 配方对象是 `RecipeHolder`，原料全是物品 | 能 | 纯物品配方适配器 |\n" % len(plain))
    out.write("| A2 需先扩展模型 | %d | 配方对象是 `RecipeHolder`，但含流体/化学品原料 | 能，槽位按原料种类打补丁 | "
              "`EditorIngredient` 携带种类 + 数量 |\n" % len(fluidic))
    out.write("| B JEI 合成页 | %d | 配方对象不是 `RecipeHolder`，由模组代码生成 | 视页面而定 | 照 `JeiVanillaRecipeEditorAdapter` 模式 |\n" % len(synthetic))
    out.write("| C 信息页 | %d | 不是配方页（战利品、图鉴、统计、多方块预览） | 不适用 | 不接入 |\n" % len(info))
    out.write("| 合计 | %d | | | |\n\n" % (len(supported) + len(plain) + len(fluidic) + len(synthetic) + len(info)))

    out.write("判据取运行时导出的 `recipeClass` 与 `shape` 两列：\n\n")
    out.write("- `recipeClass` 含 `RecipeHolder` → 页面显示的是注册表里的配方对象，背后有配方 JSON，"
              "属于可编辑的 A 组；\n")
    out.write("- `shape` 里出现 `fluid_stack` 等非 `item_stack` 原料 → A2 组；\n")
    out.write("- 两者都不满足 → B/C 组。\n\n")

    out.write("## 0 组：已支持的 vanilla 页面（%d）\n\n" % len(supported))
    out.write("| 页面 uid | 配方数 | JEI 原料槽（入/出） | 状态 |\n| --- | --- | --- | --- |\n")
    for row in sorted(supported, key=lambda r: r["uid"]):
        ins, outs = slots(row["shape"])
        out.write("| `%s` | %s | %d / %d | done |\n" % (row["uid"], row["recipes"], ins, outs))

    out.write("\n> 注：`minecraft:smithing` 页面里混入了其他模组的锻造配方"
              "（示例配方 serializer 为 `silentgear:smithing/upgrade`），"
              "这些非 vanilla serializer 的子项当前由兜底路径处理，需要单独核对。\n")

    out.write("\n## A1 组：可直接接入的配方页面（%d）\n\n" % len(plain))
    out.write("配方对象是 `RecipeHolder`，且槽位原料全是普通物品，是第一批实现目标。\n")
    group_by_namespace(out, plain, True)

    if VANILLA_BACKED:
        out.write("\nA1 表里状态 `done` 但 serializer 列写着 vanilla 名称的 %d 页，显示的**就是**"
                  "配方管理器里那条 vanilla 配方（id 未被改写、serializer 也没变），写回走它自己的 "
                  "vanilla 适配器，因此既不需要声明、也不能加声明（按 serializer 的声明会把页面从 "
                  "vanilla 适配器手里拿走）。它们的 `done` 由 `scripts/jei-client-tests.ps1` 断言"
                  "（每页至少一个模型），写回形状由 `scripts/jei-declared-smoke.ps1` 里对应 serializer "
                  "的用例钉住：\n\n" % len(VANILLA_BACKED))
        out.write("| 页面 uid | 配方数 | 显示的配方 serializer | 说明 |\n| --- | --- | --- | --- |\n")
        for uid in sorted(VANILLA_BACKED):
            row = [r for r in rows if r["uid"] == uid][0]
            out.write("| `%s` | %s | `%s` | %s |\n"
                      % (uid, row["recipes"], VANILLA_BACKED[uid], row["title"][:24]))

    if RESOLVED_VANILLA_UIDS:
        out.write("\n另有两页的 serializer 列写着模组自己的名字，但**那条 id 指向的真正配方**是 vanilla 的："
                  "Create 把这两页的条目包成自己的 `BasinRecipeHolder` 展示，而 id 落到配方管理器里"
                  "仍是 `minecraft:crafting_shaped` / `crafting_shapeless`，编辑器按 identity/`byKey` 找到"
                  "真配方再按 vanilla 分支写回（与 `mekanism:smelting` 的别名同一情形，只是 id 没被改写，"
                  "静态规则看不出来，所以在这里具名）：\n\n")
        out.write("| 页面 uid | 配方数 | 说明 | 客户端建模 |\n| --- | --- | --- | --- |\n")
        for uid in sorted(RESOLVED_VANILLA_UIDS):
            row = [r for r in rows if r["uid"] == uid][0]
            out.write("| `%s` | %s | %s | 见 §门禁页面的建模覆盖 |\n"
                      % (uid, row["recipes"], row["title"][:24]))

    if TAG_PAGE_UIDS:
        out.write("\nJEI 自己的三个标签页（`minecraft:tag_recipes/{item,block,fluid}`）同样是 `done` "
                  "但**没有**声明：它们显示的既不是 `RecipeHolder` 也不是 `Recipe`，而是 "
                  "`TagInfoRecipe`（一个标签的成员列表），所以没有配方 JSON 可写。编辑器的做法是把页面"
                  "建模成**一个** `input.0` 槽（放着该标签的第一个成员）并改写标签本身："
                  "一个标签可以有 1052 个成员（`minecraft:item`），而 `EditorModel` 最多 128 个槽，"
                  "且每次操作只命名一个成员，所以「一成员一槽」既建不出来也用不上。"
                  "每次操作走标签补丁（serializer `jeieditor:tag`，`tag.op` 为 "
                  "`member.add`/`member.remove`/`tag.create`/`tag.delete`），配方 id 里编码了"
                  "**注册表**与标签 id（`jeieditor:tag/<item|block|fluid>/<tag namespace>/<tag path>`），"
                  "因为标签 id 在不同注册表里会重名（`minecraft:planks` 同时是物品标签和方块标签），"
                  "而世界 SavedData、导入包与服务端权限闸门都按这个 id 寻址。"
                  "方块标签的成员是方块 id，而页面显示、拖放能带上的只有**物品**，所以落进方块标签的原料"
                  "要换回它放置的方块（`BlockItem#getBlock`），放不出方块的物品（木棍）被拒：\n\n")
        out.write("| 页面 uid | 配方数 | 编辑操作 | 客户端建模 |\n| --- | --- | --- | --- |\n")
        for uid in sorted(TAG_PAGE_UIDS):
            row = [r for r in rows if r["uid"] == uid][0]
            out.write("| `%s` | %s | 加/删成员、由某个成员建标签、删标签 | 见 §门禁页面的建模覆盖 |\n"
                      % (uid, row["recipes"]))

    out.write("\n## A2 组：含流体/化学品原料的配方页面（%d）\n\n" % len(fluidic))
    out.write("这些页面的槽位里含流体/化学品等非物品原料。`EditorIngredient` 现在携带"
              "**原料种类**（`IngredientKind`：item / fluid / chemical）、资源 id 和**数量**，"
              "数量范围按种类区分（物品 1..64；流体/化学品是 mB 体积，1..1000000），"
              "补丁字段名也随之分种类：物品沿用 `.item`/`.count`，流体是 `.fluid`/`.fluid_amount`，"
              "化学品是 `.chemical`/`.chemical_amount`。声明用 `withFluidFields`/`withChemicalFields`"
              "标出每个字段的种类，客户端只对得上种类的槽位给出拖放目标，服务端拒绝种类不符的补丁，"
              "写回时按该模组自己的 stack JSON 形状生成（IE 的流体输入是"
              "`{\"fluid\":id,\"amount\":n}`，流体结果是 `{\"amount\":n,\"id\":id}`；"
              "Mekanism 的化学品输入是 `{\"amount\":n,\"chemical\":id}`，输出是 `{\"amount\":n,\"id\":id}`）。\n\n")
    a2_declared = sum(1 for row in fluidic if status_of(row) == "declared")
    a2_gap = sum(1 for row in fluidic if status_of(row) == "gap")
    out.write("本组 %d 页里 %d 页已声明、%d 页标 `gap`（共用已声明 serializer 但配方在 reload 时生成、"
              "没有配方 JSON 可打补丁），`todo` 0 页；每页的具体成因见各声明文件里"
              "紧邻该页的注释。\n\n" % (len(fluidic), a2_declared, a2_gap))
    out.write("这一轮为「声明语言本身挡住的页面」补了四样最小能力，机制都只在声明层与写回层，"
              "不针对某个模组特判（只有具体推导写在对应模组的声明文件里）：\n\n")
    out.write("- **按页面作用域的声明**（`withPageUid`）：一个 serializer 对应两个 JEI 页面、且两页"
              "槽位角色相反时（`mekanism:rotary`：`condensentrating` 是化学品入 + 流体出，"
              "`decondensentrating` 反过来，两页读同一份配方 JSON），按 serializer 的声明只能描述其中"
              "一页。页面作用域声明用它自己的 uid 作键，客户端把该 uid 当作补丁的 `serializer` 发出去，"
              "服务端按同一个键取回声明；serializer 本身因此不再被声明，只报 serializer 的补丁会被"
              "`stale or unsupported patch` 拒掉，不会落进任意一个方向；\n")
    out.write("- **裸数量的字段 + 固定 id**（`ModdedJsonStyle.AMOUNT` + `withFixedFieldIds`）："
              "IE 焦炉的副产物写成 `\"creosote\": 250`，字段值就是数量、流体由字段名隐含。这种字段"
              "写回时不产生对象，并且声明必须固定它的 id，否则拖入另一种流体也会只写数量、被读回成 "
              "creosote；客户端建模时只接受该 id 的候选，写回时拒绝别的 id；\n")
    out.write("- **混合种类的有序段列表**：段列表原先要求所有段同一种类，而 `mekanism:reaction` 的"
              "可选输出是「有则加一个物品产出、有则再加一个化学品产出」，两段种类不同。现在客户端"
              "对段列表的输出槽接受任一声明过的种类（由页面上真正的原料决定），服务端在写回时按"
              "配方 JSON 解析出的那一段字段核对种类，因此 `output.0` 是物品而 `output.1` 是化学品也能"
              "各自写对；\n")
    out.write("- **配方推导的槽位序列**（`DerivedSlotSequence`，实现见 `CreateBasinSlotSequence`）："
              "`create:mixing` / `create:packing` 的槽位不是字段列表能命名的——`BasinCategory.setRecipe` "
              "先用 `ItemHelper.condenseIngredients` 把 `Ingredient.getItems()` 相同的物品原料合并成"
              "一个槽（`compacting/ice.json` 的 9 条 `snow_block` 只画一个槽），再把全部物品槽排在全部"
              "流体槽之前，而配方 JSON 里既没有这个合并也没有这个重排。声明只写下「这个槽位序列由这个"
              "推导产生」：客户端没有配方 JSON，因此只校验该推导允许的槽数下界与它声明的种类；服务端"
              "按同一份 JSON 与模组加载好的配方推出整条序列，再按补丁里的槽位序号取回该槽的全部 JSON "
              "路径——合并槽的多个入口一次全写同一个值，所以重建页面画出同样的合并布局。推导对不上"
              "配方（物品入口数与模组自己加载的 `Ingredient` 列表不一致、或逐项 `getItems()` 不同）时"
              "整份补丁被拒，不写猜测结果；\n")
    out.write("- **嵌套原料节点只改写 base**（`withPreservedIngredientBase`）：`mekanism:painting` 的 "
              "`item_input` 在 176 份文件里有 160 份是 "
              "`{\"type\":\"neoforge:difference\",\"base\":…,\"count\":1,\"subtracted\":…}`，页面上"
              "显示的正是 base 减 subtracted。按普通物品字段写回会把整个节点换成 `{\"item\":…,\"count\":…}`、"
              "丢掉 `subtracted`；声明把这个字段标成「保留嵌套结构」后，写回只替换 `base`，`type`/"
              "`count`/`subtracted` 原样保留，重建页面仍是同一条 difference。\n\n")
    group_by_namespace(out, fluidic, True)

    out.write("\n## B 组：JEI 合成页（%d）\n\n" % len(synthetic))
    out.write("页面对象由模组代码生成，不来自配方 JSON。可以编辑的只有少数"
              "（例如 `horsepowered:*`、`cobblemon:*` 是模组自己的数据驱动配方），"
              "其余基本是展示型页面。JEI 自己的三个标签页"
              "（`minecraft:tag_recipes/{item,block,fluid}`）也在这一组里，它们是 `done` 但没有声明："
              "编辑的是标签本身，见 A1 组后半的标签页说明。\n")
    group_by_namespace(out, synthetic, False, True)

    out.write("\n## C 组：信息页（%d，不适用可视化编辑）\n\n" % len(info))
    out.write("战利品表、图鉴、世界生成、附魔/效果说明、多方块预览等，本身不是配方，"
              "没有可落盘的编辑操作。\n")
    group_by_namespace(out, info, False)

    out.write("\n## 客户端实测\n\n")
    out.write("`scripts/jei-client-tests.ps1` 照 JEI 本体的做法搭了一套客户端自动测试："
              "把 `clientTest` source set 编成第二个测试 mod（`jeieditortests`），"
              "挂在一个独立的 `clientTest` run 里（ModDevGradle 的 `sourceSet` + `loadedMods`），"
              "客户端进世界后遍历 JEI 注册的全部页面，用编辑器自己的建模路径建模型并断言，"
              "结果写到 `build/client-test/report.json`。用例覆盖：\n\n")
    out.write("- **A** 清单里每个已接入页面至少要有一个配方能建模；\n")
    out.write("- **B** 声明页面的槽键必须正好是 `input.0..input.N-1` + `output`，N 等于声明的输入字段数；"
              "每个槽的原料种类必须是声明为该字段（有序段列表则为其任一段）的种类，"
              "页面上该槽真正显示的原料种类也必须在声明里（否则那槽画得出来却永远拖不进去），"
              "声明为流体/化学品的槽位不能为空；\n")
    out.write("- **C** 补丁往返：每个输入槽与产出槽都要么产出带该槽位自身键的 patch 字段，要么按契约拒绝"
              "（声明页里模型没解析出种类的槽位、JEI 合成页的空产出槽都必须抛 `IllegalArgumentException`；"
              "合成网格的空格子必须能填进去）；非物品槽位按自己的种类断言——物品拖放必须被拒，"
              "同种类拖放（经 `RecipeAdapterSupport.editorIngredient` 从 JEI 注册的原料解析，和拖放路径同一条）"
              "必须产出该槽位的 `.fluid`/`.fluid_amount` 或 `.chemical`/`.chemical_amount`；"
              "物品槽位反向断言拒绝流体拖放；\n")
    out.write("- **D** 报告编辑器能建模但清单没有声明的页面（防止误匹配）。\n\n")
    out.write("每页最多记录 3 个建模样本的完整槽位内容（`modelled_samples` 里 `{key=role:kind:id:amount;…}` 那段），"
              "服务端探针用例用它复现同一模型的 `base_fingerprint`（指纹哈希的就是"
              "`kind:id:amount`，所以流体槽位不会和同 id 同数量的物品槽位撞哈希）：`scripts/jei-declared-smoke.ps1` 只把"
              "声明页面的指纹当松弛值，未声明页面必须给出真实指纹，否则补丁会被当作过期改动拒绝。\n\n")
    wired_total = sum(1 for row in rows if status_of(row) in ("done", "declared"))
    out.write("%d 个已接入页面（清单里状态为 `done`/`declared` 的行）都要在客户端测试里至少建出一个模型；"
              "接入过程中实测发现的三类问题：\n\n" % wired_total)
    out.write("| 页面 | 现象 | 性质 |\n| --- | --- | --- |\n")
    out.write("| `immersiveengineering:arc_recycling` | 0 个模型 | 与 `arc_furnace` 共用 serializer，"
              "但它的配方在 reload 时由 `arc_recycling_list.json` 生成（没有自己的配方 JSON 可打补丁），"
              "声明把它列为只读页 → 清单不能算它已接入 |\n")
    out.write("| `minecraft:tag_recipes/item`、`/block`、`/fluid` | 每页至少 1 个模型 | 早期是 0 个模型："
              "JEI 直接推 `TagInfoRecipe`，既不是 `RecipeHolder` 也不是 `Recipe`，编辑器所有路径都不认。"
              "现在这三页走**标签补丁**这一支：模型是一个 `input.0` 槽，操作是 `member.add`/"
              "`member.remove`/`tag.create`/`tag.delete`（见 0/A1 组后面的标签页说明） |\n")
    out.write("| `jei:information`、`minecraft:compostable`、锻造纹饰配方 | 产出槽为空 | 不是缺陷："
              "编辑器的契约就是产出槽为空时不可编辑，客户端测试按同一契约断言 |\n\n")
    out.write("另外实测到 7 个**没有声明却能建模**的页面，其中 6 个已经按事实记入清单（见 A1 组的两张说明表）："
              "`create:automatic_packing`、`automatic_shaped`、`automatic_shapeless`、`block_cutting`、"
              "`fan_blasting`、`fan_smoking`。它们能建模是因为页面上显示的配方最终就是编辑器真正实现的 "
              "serializer 的配方：`block_cutting` 是 `minecraft:stonecutting`，"
              "`fan_blasting`/`fan_smoking` 是 `minecraft:smelting`/`smoking`，而三个 `create:automatic_*` "
              "页面的补丁携带的是配方管理器里那份底层配方的 crafting serializer（dump 里的 `create:basin` "
              "只是为 JEI 展示转换出来的 holder 的 serializer），写回走 vanilla 分支，所以既不需要声明、"
              "也不能加声明（按 serializer 的声明会把页面从 vanilla 适配器手里拿走）。"
              "最后一个 `jerm:jerm_repair` 仍是「能建模但不记入」：它是 JEI 自己的铁砧页，走 `jei:*` 合成页路径，"
              "编辑由 UI 与服务端的只读策略拒绝，因此留在客户端的「报告但不算失败」一侧。\n\n")
    out.write("vanilla 适配器只认自己实现的 serializer（`RecipeEditorAdapters.isImplementedVanillaSerializer`），"
              "不按配方类匹配，所以这份名单比收紧前短：`silentgear:gear_crafting` 的 "
              "`silentgear:compound_part` 配方类继承 `ShapelessRecipe`，曾被当作合成网格 offer 出来，"
              "而服务端对它的补丁一律回答 `stale or unsupported patch`（补丁从未落盘），收紧后该页回到 0 模型；"
              "本轮改为给该页的两个 serializer（`silentgear:gear_crafting`、`silentgear:compound_part`）"
              "出**声明**，补丁改走声明路径，页面因此重新可编辑。"
              "`create:automatic_shaped`（39 → 32 个模型）与 `create:automatic_shapeless`（40 → 24）"
              "只失去 serializer 不是 vanilla crafting 的子项，例如 "
              "`refinedstorage:recoloring`——它自己的 codec 要 `ingredient`/`dye`，而重建出来的是 "
              "`ingredients`/`result`，写出的 JSON 读不回来。"
              "`create:automatic_packing` 不受影响：它的样本全部携带 `minecraft:crafting_shaped`/"
              "`crafting_shapeless`。\n\n")
    out.write("门禁除了「已接入必须至少建出一个模型」，还反过来断言 `gap` 行必须建出**零**个模型。"
              "这样缺口是被断言的事实而不是被忽略的行：哪天有人修好了，门禁会红，提示回来改本清单。"
              "客户端测试脚本里另有一份「已知不可用」的锁定名单（`$knownUnsupported`），目前为空："
              "它原先锁着三个标签页，那三页接入标签补丁之后由清单按 `done` 读入、按已接入断言"
              "（每页至少一个模型）。\n\n")
    out.write("### 实测查出的编译器缺陷\n\n")
    out.write("`KNOWN UNSUPPORTED` 这条新断言上线后第一次运行就红了，而且不是文档问题："
              "JEI 的标签页（`TagInfoRecipe`）不是 `RecipeHolder`，`JeiRecipeIntrospection.recipeId` 会回落到"
              "类别的注册名，也就是**物品标签 id**；`resolveRecipeHolder` 拿这个 id 去 "
              "`recipeManager.byKey(...)` 查，一旦标签 id 和某个配方 id 撞名（本包里 "
              "`silentgear:blueprint_paper`、`cobblemon:black_tumblestone_bricks` 都撞过），"
              "就会返回一个**毫不相干的配方**，于是标签页变成可编辑，而补丁会落到那个配方上。\n\n")
    out.write("修法：注册表查表只对 `RecipeHolder` 生效（它的 `id()` 才是配方自己的 id），"
              "其它对象一律走按实例的身份扫描。修完连跑两次，188 个页面的模型数逐页完全一致，"
              "标签页稳定为 0，8 个非 `RecipeHolder` 的已接入页面模型数不变。\n\n")
    out.write("### 运行时合成页面的真实来源（服务端实测）\n\n")
    out.write("「模组在运行时合成、没有配方 JSON 可打补丁」是本清单里 `todo`/`gap` 最常见的成因，"
              "所以它被单独核对过：一次性服务端探针在 68 jar 的专用服务器里"
              "逐条问了两件事——配方在不在 `RecipeManager` 里（`byKey(id)` 与 `getOrderedRecipes()`），"
              "以及 `server.getResourceManager().getResource(<ns>:recipe/<path>.json)` 能不能解析出配方 JSON。"
              "先问「资源能否解析」是因为 Create 会注册一个运行时 `DynamicPack`：若它把合成配方的 JSON 也放了进去，"
              "那这些页面本来就能用生成的数据包文件覆盖，不需要新机制。该探针的模板与驱动脚本入库在 "
              "`scripts/jei-category-dump/`（`RecipeProvenanceProbe.java` + `probe-provenance.ps1`，"
              "照 `JeiCategoryDiagnostic` 的做法临时拷进 target、跑完删掉），所以下面的数字可以重测。实测结果：\n\n")
    out.write("| serializer / 族 | 管理器里的条数 | 其中 JSON 可达 | 真正来源 |\n| --- | --- | --- | --- |\n")
    out.write("| `create:emptying`（`create:draining`，页面 80 条） | 2 | 2 | 其余 78 条只存在于 JEI 客户端列表："
              "`ItemDrainCategory#consumeRecipes` 按物品的流体 capability 现算配方（id 形如 "
              "`create:empty_<item>_of_<fluid>`），管理器里没有、也没有资源文件；Create 的 `DynamicPack`"
              "（`Pack$Position.BOTTOM`）只放 cutting/washing 配方与标签 |\n")
    out.write("| `create:filling`（`create:spout_filling`，页面 50 条） | 12 | 12 | 同上，"
              "`SpoutCategory#consumeRecipes` 现算 `fill_<item>_with_<fluid>`；12 条数据包配方已可用。"
              "本轮已按 `SpoutCategory.setRecipe` 的槽序（item → fluid → 产出）推导出声明"
              "（`CreateSpoutSlotSequence`），这一页现在是 `declared`，覆盖率 12/50 |\n")
    out.write("| `create:mixing`（`create:automatic_brewing`，页面 286 条） | 13 | 13 | 286 条来自 "
              "`PotionMixingRecipes#createRecipes(Level)` 的静态表（读 vanilla `PotionBrewing` 现造 "
              "`RecipeHolder`），世界里的机械搅拌器也直接读这份缓存，从不写进管理器 |\n")
    out.write("| `immersiveengineering:arc_furnace`（`arc_recycling`，页面 188 条） | 54 | 54 | 188 条子配方由 "
              "IE 的 `CachedRecipeList#updateCache` 从唯一的生成器配方 "
              "`arc_recycling_list.json` 展开，只存在 IE 自己的缓存里；管理器里只有那个 "
              "`immersiveengineering:generated_list` 生成器本身 |\n")
    out.write("| `mekanism:smelting`（页面 284 条） | 0 | — | 页面显示的就是 vanilla `minecraft:smelting` "
              "那 284 条配方，只是被 `RecipeViewerUtils.synthetic(id, \"mekanism_generated\")` 改了 id；"
              "底层配方在管理器里、JSON 也可达 → 按别名接入（见下） |\n")
    out.write("| `jearchaeology:brush` / `sniff` | 0（无玩家时） | — | 配方由 `Helper#getAllBrushingRecipes` "
              "从战利品表现造，再由 `OnDatapackSyncEvent` 用 `replaceRecipes` 推进管理器；"
              "该事件按玩家触发，所以无玩家的专用服务器上一条都没有，jar 里也没有任何 `data/**` 配方文件 |\n")
    out.write("| `mekanism:nutritional_liquifier`（页面 89 条） | 0 | — | 页面对象是 "
              "`BasicItemStackToFluidOptionalItemRecipe`，它的 `getType()`/`getSerializer()` 都是 null，"
              "是纯粹的查看器列表（`FakeRVRecipeType`），永远不是数据包配方 |\n\n")
    out.write("最关键的一条是全局扫描：**管理器里 8576 条配方，没有一条的配方 JSON 不可达**"
              "（同一个探针的 `all:` 查询，`withoutResource=0`）。也就是说「在管理器里、但没有可写配方 JSON」"
              "这个集合在本包里是空的——持久化补丁并在载入/重载时替换管理器里那条配方的机制因此没有对象；"
              "要支持上表里的页面，缺的不是写回机制，而是那些配方根本不在服务端的管理器里。\n\n")
    out.write("`mekanism:smelting` 是这张表里唯一能真正编辑的一族：页面显示的 284 条与 vanilla "
              "`minecraft:smelting` 的 284 条一一对应（JEI 的 `minecraft:smelting` 页也是 284 条），"
              "例如 `cobblemon:/mekanism_generated/passho_berry_smelt_to_dye` 就是数据包里的 "
              "`cobblemon:passho_berry_smelt_to_dye`。编辑器按 `RecipeViewerAliases` 里的别名规则"
              "（页面 uid → 该页插入的路径前缀 `/mekanism_generated/`）把显示出来的 id 还原成真实 id，"
              "再按那条配方的 serializer（vanilla `minecraft:smelting`）建模并写回生成数据包，"
              "所以这一页在表里的 serializer 列是页面自己的 serializer，而补丁携带的是底层那条配方的。\n\n")
    out.write("### 已接入页面的真实覆盖率\n\n")

    out.write("声明只匹配一种槽位形态，所以「页面建模成功」不等于「整页可编辑」。实测（每页最多采样 40 条）：\n\n")
    out.write("- 满覆盖（采样数即模型数）：`mekanism:combining`/`crushing`/`enriching`/`sawing` 40/40、"
              "`create:crushing`/`deploying`/`milling`/`sawing`/`fan_washing` 40/40、"
              "`immersiveengineering:arc_furnace`/`blueprint`/`crusher`/`sawmill` 40/40、`metal_press` 24/24、"
              "`horsepowered:chopping` 25/25、`grinding` 与 `manual_grinding` 28/28 等；\n")
    out.write("- 部分覆盖：`minecraft:crafting` 37/40、`minecraft:smithing` 21/40"
              "（这两个不是声明页，未建模的子项本来就是别的 serializer 的配方）。\n")
    out.write("- A2 组（流体/化学品槽位）已声明的 24 页里 23 页满覆盖：这一轮接入的 "
              "`mekanism:condensentrating`/`decondensentrating` 各 17/17（全部 17 条配方）、"
              "`mekanism:reaction` 14/14（全部 14 条）、`immersiveengineering:coke_oven` 3/3（全部 3 条）"
              "与采到 40 条上限的 `mekanism:painting` 40/40 都在其中；唯一例外仍是 "
              "`immersiveengineering:refinery` 3/4：第 4 条配方（`refinery/acetaldehyde`）只有一个"
              "流体输入槽，而声明要求两个，声明描述不了这一形状就不建模。采到 40 条上限的页面"
              "（`mekanism:injecting` 40/40、`pigment_extracting` 40/40）实际配方更多，所以是"
              "「采样内满覆盖」而非全量。\n")
    out.write("- 新接入页面里**空槽位**的真实情况也被断言出来了：`create:item_application` 的 28 条模型里"
              "有 20 个输入槽读不出原料（整包里该 serializer 只有 8 个配方 JSON 文件，每个都带两个 "
              "`ingredient`，28 条页面上另有来源），编辑器的契约是拒绝这种槽位，客户端测试按同一契约断言"
              "（拒绝数与检查数一起写进 `patch_roundtrip`）；`create:automatic_shaped` 这类非已接入页面的"
              "同类情况只记录、不算失败。\n\n")
    out.write("门禁只要求每页至少一个模型，所以部分覆盖目前不会失败；要设覆盖率下限得另加断言。\n\n")

    out.write("## TODO 波次\n\n")
    out.write("| 波次 | 范围 | 目标 | 状态 |\n| --- | --- | --- | --- |\n")
    out.write("| W0 | 框架 | 声明式模组配方适配器（每模组一份声明 + 按 JEI 槽视图建模 + 原始 JSON 打补丁） | done |\n")
    plain_declared = sum(1 for row in plain if status_of(row) == "declared")
    plain_done = sum(1 for row in plain if status_of(row) == "done")
    plain_gap = sum(1 for row in plain if status_of(row) == "gap")
    plain_todo = sum(1 for row in plain if status_of(row) == "todo")
    out.write("| W1 | A1 组 | A1 表 %d 页：已声明 %d 个、vanilla 适配器直接接管的 %d 个、`gap` %d 个、"
              "仍是 todo %d 个；另有 %d 个非 RecipeHolder 页"
              "（B 组）也已声明。未声明页的成因有三类：serializer 本身就是 vanilla 或与别的模组共用"
              "（写回走 vanilla 适配器，不能再加声明，否则会顶掉 vanilla 页）；配方由模组在运行时生成、"
              "没有配方 JSON 可打补丁；网格或多输入数组形状。`immersiveengineering:sawmill` 与 "
              "`arc_furnace` 的多种输出形状已由「有序段列表」（`withConcatenatedOutputs`：按 JEI 类别的"
              "加槽顺序把单段/重复段拼接到配方 JSON 上，客户端只看槽数、服务端按 JSON 解析具体字段）接入；"
              "`mekanism:smelting` 显示的就是 vanilla `minecraft:smelting` 的配方本身，只是 id 被模组改写，"
              "因此按别名还原成真实配方后接入（见「运行时合成页面的真实来源」）"
              " | in_progress |\n"
              % (len(plain), plain_declared, plain_done, plain_gap, plain_todo, len(DECLARED_UIDS)))
    out.write("| W2 | A2 组 | 含流体/化学品的 %d 页：声明 %d 页、`gap` %d 页，其余 %d 页仍未接入——"
              "`create:spout_filling` 已按 `SpoutCategory.setRecipe` 的槽序（item → fluid → 产出）"
              "由 `CreateSpoutSlotSequence` 接入（覆盖 12/50，其余是运行时生成或条件未满足的文件）；"
              "`create:draining` 试过并**回退**：按 `ItemDrainCategory` 的槽序（item → fluid → item）"
              "写出的声明让 40 条采样全部建模，但其中 78/80 的条目是现算的（管理器里没有），"
              "它们画出的输入槽是带组件补丁的堆叠、物品产出为空，客户端测试的派生形状断言因此报出 "
              "57 条失败（`draws 1 input slots but its item band (0) and fluid band (0) hold 0`、"
              "`leaves the item/fluid slot output.1 empty`），所以该页按事实记 `gap`。"
              "回退的原因**不是**路径寻址：本轮的空路径槽（`DerivedSlot` 的 paths 为空）已经能表达"
              "「这个槽没有字段」，但 draining 卡住的是**模型侧解析**——页面上那个槽显示的是带组件补丁的"
              "堆叠、产出槽是空的，编辑器的物品原料只有 `id + count`，解析不出代表原料，而派生契约要求"
              "声明了非纯物品种类的页面不能留一个解析不出来的槽。它需要的是「一个模型解析不出来的槽」"
              "这类表达能力（外加最小的原料模型扩展），而不是一条新声明。"
              "（`create:automatic_brewing` 与 `create:mixing` 共用 serializer，声明把它列为只读页，"
              "清单因此记 `gap`。）这些数字来自「运行时合成页面的真实来源」一节的专用探针。原先被挡住的 "
              "`create:mixing` / `create:packing` 已由**配方推导的槽位序列**接入：它们的槽位顺序来自 "
              "`ItemHelper.condenseIngredients` 的合并与「物品槽在前、流体槽在后」的重排，字段列表"
              "推不出来，所以声明把这个推导本身写下来（见 A2 组说明里的第四条）；IE 的流体页"
              "（`refinery`/`mixer`/`coke_oven`/`fermenter`/`squeezer`）与需要序列的 "
              "`cloche`/`bottling_machine`/`fertilizer`/`blast_furnace_fuel` 也已全部接入 | in_progress |\n"
              % (len(fluidic), a2_declared, a2_gap, len(fluidic) - a2_declared - a2_gap))
    out.write("| W3 | B 组 | 普查完成：%d 页里 %d 页已逐页判定（各自理由写在生成器的集合与注释里："
              "已声明、按标签补丁类型接入（三个 `minecraft:tag_recipes/*`）、或按事实记 `gap`；"
              "`todo` 已清空），候选调查见 "
              "`build/tmp/jei-synthetic-survey.md`。剩下的都不是「再写一条声明」能解决的，见 W4 "
              "| in_progress |\n" % (len(synthetic), len(synthetic)))
    out.write("| W4 | 新机制 | 三个「需要新机制」的页面都已用**派生契约**接入："
              "`cobblemon:campfire_pot_recipe` 与 `create:mechanical_crafting` 靠 "
              "`DerivedSlotSequence.fieldBackedInputOrdinals` 告诉客户端「哪些输入槽位有字段」"
              "（campfire_pot 的 seasoning 预览槽在**末尾**、被建模成只读；mechanical_crafting 的"
              "`pattern` 按**稠密格子**一格一槽，空格格的空路径夹在字母格**中间**，补丁打到它上面会被整条拒绝）；"
              "`create:sequenced_assembly` 靠**逐槽路径**——其槽位顺着 `sequence` 的每一步排布"
              "（base `ingredient`、deploying 画该步第 2 个物品、filling 画该步第 1 个流体、pressing/cutting 不画），"
              "所以序号到字段是一张路径表而不是模式。仍需要机制的只有 `create:draining`"
              "（试接后按 57 条实测失败回退：它卡在模型侧解析——输入槽是带组件补丁的堆叠、物品产出为空，"
              "需要「一个模型解析不出来的槽」这类表达能力，空路径槽解决不了它） | pending |\n")
    out.write("| — | C 组 | 不接入 | n/a |\n")

    out.write("\n## 复现\n\n")
    out.write("```powershell\n")
    out.write("# 1) 把诊断模板临时放回模组源码（用完必须删除，不能提交）\n")
    out.write("copy scripts\\jei-category-dump\\JeiCategoryDiagnostic.java targets\\neoforge-1.21.1\\src\\main\\java\\cc\\sighs\\JEIEditor\\client\\\n\n")
    out.write("# 2) 启动客户端；诊断会自动创建一个临时世界并在 JEI 启动后导出分类\n")
    out.write("powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\jei-category-dump\\dump-categories.ps1 -TimeoutSeconds 1800\n\n")
    out.write("# 3) 交叉验证（静态扫描，可离线跑）\n")
    out.write("python scripts\\jei-category-dump\\scan_categories.py\n\n")
    out.write("# 4) 由导出结果重新生成本清单（模组集合变化后重跑）\n")
    out.write("python scripts\\jei-category-dump\\gen_checklist.py\n")
    out.write("```\n\n")
    out.write("`run/jei-category-dump.txt` 属于被忽略的 `run/`，不进版本库。\n")

    with open(OUT, "w", encoding="utf-8") as handle:
        handle.write(out.getvalue())
    print("wrote %s (%d bytes)" % (OUT, len(out.getvalue())))


main()
