# End-to-end check for the declared mod recipe types (create/IE/Mekanism/...).
#
# Boots the dedicated server with run/mods, imports a bundle of patches that
# target real modded recipes, then asserts the generated datapack JSON:
#   * the edited slot was rewritten in the mod's own field shape,
#   * every other key of the mod's recipe survived untouched,
#   * the server could parse the result back into a recipe (apply fails otherwise),
#   * the files survive a /reload.
#
# It then probes the pages the editor models without a declaration (create:automatic_packing,
# jerm:jerm_repair and friends): one patch per page, imported on its own, asserting either the
# written JSON or that the server refuses the patch and leaves no file behind. One probe per
# family whose recipes are synthesised outside the recipe manager (Create's draining/spout
# pages, IE's arc recycling, Just Enough Archaeology, Mekanism's alias and smelting pages)
# pins the same refusal for the ids those pages really display - the provenance behind them
# is measured and recorded in docs/JEI_PAGE_COMPAT_TODO.md, "运行时合成页面的真实来源".
#
# With -RestartCycle it stops and starts the server once more and proves on the live recipe
# that the world still loads the generated pack: the probe patch of that cycle carries the
# fingerprint of the already edited recipe, so only a server holding the edited values can
# accept it.
#
# This is the only automated evidence that the declared write path produces JSON
# the mod's own codec accepts. It needs no GUI.
[CmdletBinding()]
param(
    [int] $RconPort = 25575,
    [int] $ServerPort = 25565,
    [string] $RconPassword = 'jeieditor-local-test',
    # Generous on purpose: on a cold clone the server has to start Gradle, load the
    # whole 97-mod pack and build its recipes before RCON opens. That was observed to
    # take about 7 minutes, so a tight default would make a first run look like a
    # failure rather than a slow start.
    [int] $StartupTimeoutSeconds = 900,
    [switch] $Offline,
    # Adds a stop/start cycle at the end: a patch whose fingerprint only exists in
    # the edited state must be accepted after the restart, which proves the
    # generated datapack is loaded again with the world (see the cycle's comment).
    [switch] $RestartCycle,
    # JDK 21 that runs Gradle. Defaults to JAVA_HOME, then to the path this repo
    # was developed on, so the script is not machine-locked.
    [string] $JdkPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# The dedicated server is launched through the target's Gradle wrapper, which needs
# a JDK 21 on JAVA_HOME.
$jdk = if ($JdkPath) { $JdkPath } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\program\jdk-21' }
if (-not (Test-Path -LiteralPath $jdk -PathType Container)) {
    throw "JDK 21 was not found at '$jdk'; pass -JdkPath or set JAVA_HOME to a JDK 21 installation."
}
$env:JAVA_HOME = $jdk

$repoRoot = Split-Path -Parent $PSScriptRoot
$targetDirectory = Join-Path $repoRoot 'targets\neoforge-1.21.1'
$wrapper = Join-Path $targetDirectory 'gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw "Gradle wrapper not found: $wrapper"
}

$runDirectory = Join-Path $targetDirectory 'run'
$logDirectory = Join-Path $targetDirectory 'build\jei-declared-smoke'
$worldName = 'jeideclared-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$worldDirectory = Join-Path $runDirectory $worldName
$policyConfigPath = Join-Path $runDirectory 'config\jeieditor.properties'
$policyBackupPath = Join-Path $logDirectory 'jeieditor.properties.original'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $policyConfigPath) -Force | Out-Null
$policyWasPresent = Test-Path -LiteralPath $policyConfigPath -PathType Leaf
Remove-Item -LiteralPath $policyBackupPath -Force -ErrorAction SilentlyContinue
if ($policyWasPresent) {
    Copy-Item -LiteralPath $policyConfigPath -Destination $policyBackupPath -Force
}
Set-Content -LiteralPath $policyConfigPath -Value @(
    'min_permission_level=2'
    'allow_namespaces='
    'deny_namespaces='
) -Encoding ascii

$stdoutPath = Join-Path $logDirectory 'server.stdout.log'
$stderrPath = Join-Path $logDirectory 'server.stderr.log'
Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

$serverPropertiesPath = Join-Path $runDirectory 'server.properties'
$serverProperties = if (Test-Path -LiteralPath $serverPropertiesPath -PathType Leaf) {
    @(Get-Content -LiteralPath $serverPropertiesPath)
} else {
    @()
}
foreach ($property in @{
    'enable-rcon' = 'true'
    'rcon.password' = $RconPassword
    'rcon.port' = [string] $RconPort
    'server-port' = [string] $ServerPort
    'level-name' = $worldName
    'online-mode' = 'false'
}.GetEnumerator()) {
    $prefix = $property.Key + '='
    $replacement = $prefix + $property.Value
    $found = $false
    $serverProperties = @($serverProperties | ForEach-Object {
        if ($_ -match ('^' + [regex]::Escape($prefix))) {
            $found = $true
            $replacement
        } else {
            $_
        }
    })
    if (-not $found) { $serverProperties += $replacement }
}
Set-Content -LiteralPath $serverPropertiesPath -Value $serverProperties -Encoding ascii
Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Value 'eula=true' -Encoding ascii

# These two mods load client-only classes in their mod constructor, so a
# dedicated server cannot start with them. They are irrelevant to the declared
# write path under test, so hide them for the run and put them back afterwards.
$clientOnlyJarPrefixes = @('chest-helper__', 'create-jei-compat__')
$hiddenJars = New-Object System.Collections.Generic.List[string]
$modsDirectory = Join-Path $runDirectory 'mods'
if (Test-Path -LiteralPath $modsDirectory -PathType Container) {
    # Adopt leftovers from an earlier run first. The finally block puts every jar it
    # hid back, but a run that is killed outright never reaches it, and the next run
    # would not even notice the renamed file - it no longer ends in .jar, so nothing
    # looks for it. The pack then stays short of a mod for good, which the client
    # test and verify.py both see as a different environment. Adding the leftovers
    # to this list makes the next run restore them.
    foreach ($file in Get-ChildItem -LiteralPath $modsDirectory -File) {
        if ($file.Name.EndsWith('.jar.server-smoke-disabled')) {
            $hiddenJars.Add($file.FullName)
            Write-Host ("  adopting leftover {0} for restoration" -f $file.Name)
        }
    }
    foreach ($jar in Get-ChildItem -LiteralPath $modsDirectory -File) {
        if (-not $jar.Name.EndsWith('.jar')) {
            continue
        }
        foreach ($prefix in $clientOnlyJarPrefixes) {
            if ($jar.Name.StartsWith($prefix)) {
                $hidden = $jar.FullName + '.server-smoke-disabled'
                Move-Item -LiteralPath $jar.FullName -Destination $hidden -Force
                $hiddenJars.Add($hidden)
                Write-Host ("  hid {0} for the server run" -f $jar.Name)
            }
        }
    }
}

# Slack fingerprint: declared patches rebuild their model server side, so the
# value only has to be a well formed fingerprint.
$slackFingerprint = '0' * 64

# Fingerprint of a model whose serializer has no declaration.
#
# Such a patch is only accepted while its base_fingerprint equals the fingerprint
# the server computes from the recipe it already holds (see
# RecipeEditsApplier.createRecipeJson): the hash is the stale-edit guard. The
# existing declared cases can use the slack value above because a declaration
# skips that comparison, but a probe of an undeclared page has to carry the real
# one.
#
# RecipeModelSupport.fingerprint hashes
#     <recipeId>|<serializerId>|[<key>=<role>[:<kind>:<id>:<amount>]]*[|<property>=<value>]*
# with SHA-256, over the model's slots in order; a slot without an ingredient
# contributes only its key and role, and <kind> is the ingredient's kind (item,
# fluid or chemical), so a fluid slot never hashes like an item slot holding the
# same id and amount. Each probe below spells that string out as BaseSlots, so the
# expected fingerprint stays readable and recomputable instead of being an opaque
# literal.
function Get-ModelFingerprint {
    param([Parameter(Mandatory = $true)][string] $Canonical)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Canonical))
    } finally {
        $algorithm.Dispose()
    }
    return (($digest | ForEach-Object { $_.ToString('x2') }) -join '')
}

$cases = @(
    @{
        Name = 'immersiveengineering:crusher'
        RecipeId = 'immersiveengineering:crusher/amethyst'
        Serializer = 'immersiveengineering:crusher'
        RecipePath = 'data\immersiveengineering\recipe\crusher\amethyst.json'
        Fields = '{"input.0.item":"minecraft:emerald_block","input.0.count":"3",' +
            '"output.item":"minecraft:nether_star","output.count":"2"}'
        # energy and the recomputed input/result are all asserted below.
        Expect = @{
            'type' = 'immersiveengineering:crusher'
            'energy' = 3200
            'input.item' = 'minecraft:emerald_block'
            'input.count' = 3
            'result.id' = 'minecraft:nether_star'
            'result.count' = 2
        }
        Absent = @('input.tag')
    },
    @{
        Name = 'mekanism:combining'
        RecipeId = 'mekanism:combining/gravel'
        Serializer = 'mekanism:combining'
        RecipePath = 'data\mekanism\recipe\combining\gravel.json'
        # input.1 is extra_input; input.0 (main_input) is left alone.
        Fields = '{"input.1.item":"minecraft:stone","input.1.count":"3"}'
        # The original file is
        #   {"type":"mekanism:combining","extra_input":{"count":1,"tag":"c:cobblestones/normal"},
        #    "main_input":{"count":1,"item":"minecraft:flint"},"output":{"count":1,"id":"minecraft:gravel"}}
        # so extra_input must lose its tag when its item is edited, while
        # main_input and output survive untouched.
        Expect = @{
            'type' = 'mekanism:combining'
            'main_input.item' = 'minecraft:flint'
            'main_input.count' = 1
            'extra_input.item' = 'minecraft:stone'
            'extra_input.count' = 3
            'output.id' = 'minecraft:gravel'
            'output.count' = 1
        }
        Absent = @('extra_input.tag')
    },
    # Repeating (variable-arity) input field: "additives.%d". Ordinal 0 is the fixed
    # "input" field and ordinal 1 is additives[0], so this patch rewrites the additive
    # entry of a recipe that also carries slag, energy and time.
    # data/immersiveengineering/recipe/arcfurnace/steel.json is
    #   {"type":"immersiveengineering:arc_furnace","additives":[{"tag":"c:dusts/coal_coke"}],
    #    "energy":204800,"input":{"tag":"c:ingots/iron"},
    #    "results":[{"tag":"c:ingots/steel"}],"slag":{"tag":"c:slag"},"time":400}
    # The client only models the single-output arc furnace shape (see the declaration), so
    # this case pins the write path: the additive is rewritten, the untouched input, result,
    # slag, energy and time survive, and the additive's old tag is gone.
    @{
        Name = 'immersiveengineering:arc_furnace (additives.%d)'
        RecipeId = 'immersiveengineering:arcfurnace/steel'
        Serializer = 'immersiveengineering:arc_furnace'
        RecipePath = 'data\immersiveengineering\recipe\arcfurnace\steel.json'
        Fields = '{"input.1.item":"minecraft:coal_block","input.1.count":"2"}'
        Expect = @{
            'type' = 'immersiveengineering:arc_furnace'
            'energy' = 204800
            'time' = 400
            'input.tag' = 'c:ingots/iron'
            'additives.0.item' = 'minecraft:coal_block'
            'additives.0.count' = 2
            'results.0.tag' = 'c:ingots/steel'
            'slag.tag' = 'c:slag'
        }
        Absent = @('additives.0.tag')
    },
    # Repeating input field: "inputs.%d" on a six-input blueprint. Only the entry the
    # patch addresses may change.
    # data/immersiveengineering/recipe/blueprint/robot_wolf.json is
    #   {"type":"immersiveengineering:blueprint","category":"automatons","inputs":[
    #      {"item":"immersiveengineering:thermoelectric_generator"},
    #      {"item":"immersiveengineering:radiator"},
    #      {"basePredicate":{"tag":"c:ingots/uranium"},"count":3},
    #      {"basePredicate":{"tag":"c:plates/steel"},"count":2},
    #      {"item":"immersiveengineering:component_electronic_adv"},
    #      {"basePredicate":{"item":"immersiveengineering:component_steel"},"count":2}],
    #    "result":{"id":"immersiveengineering:robot_wolf"}}
    # The old two-entry declaration could not name inputs.2 at all; the repeatable
    # "inputs.%d" names every entry, and the other five plus "category" and "result"
    # must survive verbatim.
    @{
        Name = 'immersiveengineering:blueprint (inputs.%d, 6 inputs)'
        RecipeId = 'immersiveengineering:blueprint/robot_wolf'
        Serializer = 'immersiveengineering:blueprint'
        RecipePath = 'data\immersiveengineering\recipe\blueprint\robot_wolf.json'
        Fields = '{"input.2.item":"minecraft:diamond","input.2.count":"3"}'
        Expect = @{
            'type' = 'immersiveengineering:blueprint'
            'category' = 'automatons'
            'inputs.0.item' = 'immersiveengineering:thermoelectric_generator'
            'inputs.1.item' = 'immersiveengineering:radiator'
            'inputs.2.item' = 'minecraft:diamond'
            'inputs.2.count' = 3
            'inputs.3.basePredicate.tag' = 'c:plates/steel'
            'inputs.3.count' = 2
            'inputs.4.item' = 'immersiveengineering:component_electronic_adv'
            'inputs.5.basePredicate.item' = 'immersiveengineering:component_steel'
            'inputs.5.count' = 2
            'result.id' = 'immersiveengineering:robot_wolf'
        }
        Absent = @('inputs.2.basePredicate', 'inputs.2.tag')
    },
    # Two-output page: output.1 is the second JEI OUTPUT slot, which the crusher draws for
    # each entry of "secondaries" (output.0 is always "result"). Both outputs are reachable,
    # and only the addressed one changes.
    # data/immersiveengineering/recipe/crusher/blue_dye.json is
    #   {"type":"immersiveengineering:crusher","energy":1600,"input":{"tag":"c:gems/lapis"},
    #    "result":{"count":2,"id":"minecraft:blue_dye"},
    #    "secondaries":[{"chance":0.1,"output":{"item":"minecraft:light_gray_dye"}}]}
    # The rewritten secondary is the declaration's result shape, i.e. {"id":…,"count":…} rather
    # than the "item" form the file used, while its sibling output, its "chance" and the input
    # stay untouched.
    @{
        Name = 'immersiveengineering:crusher (secondaries, output.1)'
        RecipeId = 'immersiveengineering:crusher/blue_dye'
        Serializer = 'immersiveengineering:crusher'
        RecipePath = 'data\immersiveengineering\recipe\crusher\blue_dye.json'
        Fields = '{"output.1.item":"minecraft:diamond","output.1.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:crusher'
            'energy' = 1600
            'input.tag' = 'c:gems/lapis'
            'result.id' = 'minecraft:blue_dye'
            'result.count' = 2
            'secondaries.0.chance' = 0.1
            'secondaries.0.output.id' = 'minecraft:diamond'
            'secondaries.0.output.count' = 1
        }
        Absent = @('secondaries.0.output.item', 'secondaries.0.output.tag')
    },
    # Two-output page whose output field repeats: "results.%d" names output.0 as results.0
    # and output.1 as results.1. The sibling entry keeps its own count, while the addressed
    # entry is rewritten in the declaration's result shape ({"id","count"}), which is why
    # its "chance" is gone - the declared style replaces the whole result node.
    # data/create/recipe/splashing/crushed_raw_iron.json is
    #   {"type":"create:splashing","ingredients":[{"item":"create:crushed_raw_iron"}],
    #    "results":[{"count":9,"id":"minecraft:iron_nugget"},
    #               {"chance":0.75,"id":"minecraft:redstone"}]}
    @{
        Name = 'create:splashing (results.%d, output.1)'
        RecipeId = 'create:splashing/crushed_raw_iron'
        Serializer = 'create:splashing'
        RecipePath = 'data\create\recipe\splashing\crushed_raw_iron.json'
        Fields = '{"output.1.item":"minecraft:diamond","output.1.count":"1"}'
        Expect = @{
            'type' = 'create:splashing'
            'ingredients.0.item' = 'create:crushed_raw_iron'
            'results.0.id' = 'minecraft:iron_nugget'
            'results.0.count' = 9
            'results.1.id' = 'minecraft:diamond'
            'results.1.count' = 1
        }
        Absent = @('results.1.chance')
    },
    # Two-output page whose output field repeats over a longer array: "results.%d" names
    # output.0 as results.0 and output.n as results.n, so ordinal 2 is reachable too.
    # data/create/recipe/milling/charcoal.json is
    #   {"type":"create:milling","ingredients":[{"item":"minecraft:charcoal"}],
    #    "processing_time":100,"results":[{"id":"minecraft:black_dye"},
    #                                    {"chance":0.1,"count":2,"id":"minecraft:gray_dye"}]}
    # MillingCategory draws one OUTPUT slot per entry of "results", so output.1 is
    # results.1. The sibling result keeps its own count and the recipe keeps
    # processing_time; the addressed entry is rewritten in the declaration's result shape
    # ({"id","count"}), which is why its "chance" is gone.
    @{
        Name = 'create:milling (results.%d, output.1)'
        RecipeId = 'create:milling/charcoal'
        Serializer = 'create:milling'
        RecipePath = 'data\create\recipe\milling\charcoal.json'
        Fields = '{"output.1.item":"minecraft:lapis_lazuli","output.1.count":"4"}'
        Expect = @{
            'type' = 'create:milling'
            'processing_time' = 100
            'ingredients.0.item' = 'minecraft:charcoal'
            'results.0.id' = 'minecraft:black_dye'
            'results.1.id' = 'minecraft:lapis_lazuli'
            'results.1.count' = 4
        }
        Absent = @('results.1.chance')
    },
    # The deepest ordinal the harness reaches: a four-result crushing recipe, addressed at
    # output.2, with the two siblings before it and the one after it left alone.
    # data/create/recipe/crushing/copper_ore.json is
    #   {"type":"create:crushing","ingredients":[{"item":"minecraft:copper_ore"}],
    #    "processing_time":250,
    #    "results":[{"count":5,"id":"create:crushed_raw_copper"},
    #               {"chance":0.25,"id":"create:crushed_raw_copper"},
    #               {"chance":0.75,"id":"create:experience_nugget"},
    #               {"chance":0.125,"id":"minecraft:cobblestone"}]}
    # CrushingCategory draws one OUTPUT slot per entry of "results" as well, so output.2 is
    # results.2. results.1 and results.3 keep their own "chance", which pins the claim that
    # only the addressed entry is rewritten.
    @{
        Name = 'create:crushing (results.%d, output.2 of 4)'
        RecipeId = 'create:crushing/copper_ore'
        Serializer = 'create:crushing'
        RecipePath = 'data\create\recipe\crushing\copper_ore.json'
        Fields = '{"output.2.item":"minecraft:glowstone_dust","output.2.count":"2"}'
        Expect = @{
            'type' = 'create:crushing'
            'processing_time' = 250
            'ingredients.0.item' = 'minecraft:copper_ore'
            'results.0.id' = 'create:crushed_raw_copper'
            'results.0.count' = 5
            'results.1.id' = 'create:crushed_raw_copper'
            'results.1.chance' = 0.25
            'results.2.id' = 'minecraft:glowstone_dust'
            'results.2.count' = 2
            'results.3.id' = 'minecraft:cobblestone'
            'results.3.chance' = 0.125
        }
        Absent = @('results.2.chance')
    },
    # --- Create's basin pages: a slot sequence the mod derives from the recipe -----
    #
    # create:mixing and create:packing draw MixingCategory / PackingCategory, both
    # extending com.simibubi.create.compat.jei.category.BasinCategory (the COMPACTING
    # PackingType of the packing page calls its setRecipe): one INPUT slot per entry of
    # ItemHelper.condenseIngredients(getIngredients()) - entries with equal
    # Ingredient.getItems() merged into one slot carrying their count - followed by one
    # per fluid ingredient, then one OUTPUT slot per item result followed by one per
    # fluid result. The recipe JSON has neither the merge nor that ordering, so these
    # pages are declared with a recipe-derived slot sequence
    # (CreateBasinSlotSequence): the server resolves the whole sequence from this exact
    # JSON plus the recipe the mod loaded, and the slot ordinal of every patched key is
    # the ordinal of the resolved sequence. A recipe the derivation cannot reproduce
    # refuses the whole patch, and a merged slot writes the same value to every JSON
    # entry it merged, so the rebuilt page draws the same merged layout.
    #
    # data/create/recipe/compacting/ice.json is nine identical
    # {"item":"minecraft:snow_block"} entries and a {"id":"minecraft:ice"} result, so the
    # page draws ONE input slot (the whole group) and one output; input.0 rewrites all
    # nine entries, which is what keeps the group intact.
    @{
        Name = 'create:packing (input.0 = a group of nine merged entries)'
        RecipeId = 'create:compacting/ice'
        Serializer = 'create:compacting'
        RecipePath = 'data\create\recipe\compacting\ice.json'
        Fields = '{"input.0.item":"minecraft:packed_ice","input.0.count":"1"}'
        Expect = @{
            'type' = 'create:compacting'
            'ingredients.0.item' = 'minecraft:packed_ice'
            'ingredients.1.item' = 'minecraft:packed_ice'
            'ingredients.2.item' = 'minecraft:packed_ice'
            'ingredients.3.item' = 'minecraft:packed_ice'
            'ingredients.4.item' = 'minecraft:packed_ice'
            'ingredients.5.item' = 'minecraft:packed_ice'
            'ingredients.6.item' = 'minecraft:packed_ice'
            'ingredients.7.item' = 'minecraft:packed_ice'
            'ingredients.8.item' = 'minecraft:packed_ice'
            'results.0.id' = 'minecraft:ice'
        }
    },
    # data/create/recipe/compacting/andesite_from_flint.json is
    #   {"type":"create:compacting",
    #    "ingredients":[{"item":"minecraft:flint"},{"item":"minecraft:flint"},
    #                   {"item":"minecraft:gravel"},
    #                   {"type":"neoforge:single","amount":100,"fluid":"minecraft:lava"}],
    #    "results":[{"id":"minecraft:andesite"}]}
    # The two flint entries merge into input.0, gravel is input.1 and the lava fluid is
    # input.2, so this pins the ordinal-to-group mapping: input.1 addresses the THIRD
    # array entry, the first two stay flint, and the fluid keeps Create's own sized shape.
    @{
        Name = 'create:packing (input.1 = the second merged group)'
        RecipeId = 'create:compacting/andesite_from_flint'
        Serializer = 'create:compacting'
        RecipePath = 'data\create\recipe\compacting\andesite_from_flint.json'
        Fields = '{"input.1.item":"minecraft:calcite","input.1.count":"1"}'
        Expect = @{
            'type' = 'create:compacting'
            'ingredients.0.item' = 'minecraft:flint'
            'ingredients.1.item' = 'minecraft:flint'
            'ingredients.2.item' = 'minecraft:calcite'
            'ingredients.3.type' = 'neoforge:single'
            'ingredients.3.fluid' = 'minecraft:lava'
            'ingredients.3.amount' = 100
            'results.0.id' = 'minecraft:andesite'
        }
    },
    # A fluid ingredient of the same page: data/create/recipe/compacting/honey.json is
    #   {"type":"create:compacting","ingredients":[{"type":"neoforge:tag","amount":1000,
    #    "tag":"c:honey"}],"results":[{"id":"minecraft:honey_block"}]}
    # so the page's only input slot is that fluid and its only output the item. Create's
    # SizedFluidIngredient codec dispatches on an explicit "type", so the edited fluid is
    # written as {"type":"neoforge:single","fluid":…,"amount":…} - the shape every shipped
    # Create recipe that names one fluid uses - and the old tag is gone.
    @{
        Name = 'create:packing (fluid input rewritten in Create''s sized shape)'
        RecipeId = 'create:compacting/honey'
        Serializer = 'create:compacting'
        RecipePath = 'data\create\recipe\compacting\honey.json'
        Fields = '{"input.0.fluid":"create:honey","input.0.fluid_amount":"1000"}'
        Expect = @{
            'type' = 'create:compacting'
            'ingredients.0.type' = 'neoforge:single'
            'ingredients.0.fluid' = 'create:honey'
            'ingredients.0.amount' = 1000
            'results.0.id' = 'minecraft:honey_block'
        }
        Absent = @('ingredients.0.tag')
    },
    # data/create/recipe/mixing/cardboard_pulp.json is four identical
    # {"tag":"create:pulpifiable"} entries followed by
    # {"type":"neoforge:single","amount":250,"fluid":"minecraft:water"}, so the page draws
    # input.0 for the four-entry group and input.1 for the water. Editing input.0 rewrites
    # all four entries and leaves the fluid, the result and the type untouched.
    @{
        Name = 'create:mixing (input.0 = a group of four merged tag entries)'
        RecipeId = 'create:mixing/cardboard_pulp'
        Serializer = 'create:mixing'
        RecipePath = 'data\create\recipe\mixing\cardboard_pulp.json'
        Fields = '{"input.0.item":"minecraft:paper","input.0.count":"1"}'
        Expect = @{
            'type' = 'create:mixing'
            'ingredients.0.item' = 'minecraft:paper'
            'ingredients.1.item' = 'minecraft:paper'
            'ingredients.2.item' = 'minecraft:paper'
            'ingredients.3.item' = 'minecraft:paper'
            'ingredients.4.type' = 'neoforge:single'
            'ingredients.4.fluid' = 'minecraft:water'
            'ingredients.4.amount' = 250
            'results.0.id' = 'create:pulp'
        }
        Absent = @('ingredients.0.tag')
    },
    # A fluid RESULT of the same page: data/create/recipe/mixing/chocolate_melting.json is
    #   {"type":"create:mixing","heat_requirement":"heated",
    #    "ingredients":[{"item":"create:bar_of_chocolate"}],
    #    "results":[{"amount":250,"id":"create:chocolate"}]}
    # so the page draws one item input and one fluid output ("output"), which is written in
    # the result-side fluid shape {"amount":…,"id":…} - not the ingredient-side
    # {"type":"neoforge:single","fluid":…} one the same declaration uses for its inputs.
    @{
        Name = 'create:mixing (item input.0 + fluid output)'
        RecipeId = 'create:mixing/chocolate_melting'
        Serializer = 'create:mixing'
        RecipePath = 'data\create\recipe\mixing\chocolate_melting.json'
        Fields = '{"input.0.item":"minecraft:cocoa_beans","input.0.count":"1",' +
            '"output.fluid":"minecraft:lava","output.fluid_amount":"500"}'
        Expect = @{
            'type' = 'create:mixing'
            'heat_requirement' = 'heated'
            'ingredients.0.item' = 'minecraft:cocoa_beans'
            'results.0.id' = 'minecraft:lava'
            'results.0.amount' = 500
        }
        Absent = @('results.0.count')
    },
    # Two-output page whose two output slots are unconditional. BlastFurnaceRecipeCategory
    # adds INPUT(input), OUTPUT(result) and OUTPUT(slag) and nothing else, so output.1 is
    # always "slag".
    # data/immersiveengineering/recipe/blastfurnace/steel_block.json is
    #   {"type":"immersiveengineering:blast_furnace","input":{"tag":"c:storage_blocks/iron"},
    #    "result":{"tag":"c:storage_blocks/steel"},
    #    "slag":{"basePredicate":{"tag":"c:slag"},"count":9},"time":10800}
    # The tagged main result and the time survive; the addressed slag is rewritten as the
    # declaration's result shape, which drops its basePredicate/tag.
    @{
        Name = 'immersiveengineering:blast_furnace (2 outputs, output.1)'
        RecipeId = 'immersiveengineering:blastfurnace/steel_block'
        Serializer = 'immersiveengineering:blast_furnace'
        RecipePath = 'data\immersiveengineering\recipe\blastfurnace\steel_block.json'
        Fields = '{"output.1.item":"minecraft:gravel","output.1.count":"3"}'
        Expect = @{
            'type' = 'immersiveengineering:blast_furnace'
            'time' = 10800
            'input.tag' = 'c:storage_blocks/iron'
            'result.tag' = 'c:storage_blocks/steel'
            'slag.id' = 'minecraft:gravel'
            'slag.count' = 3
        }
        Absent = @('slag.tag', 'slag.basePredicate')
    },
    # Two-output page where the second slot is optional per recipe. SawmillRecipeCategory
    # registers both OUTPUT slots unconditionally; output.0 is always "main_output" and
    # output.1 is "secondary_output", which 84 of the 124 recipes carry.
    # data/mekanism/recipe/sawing/bed/black.json is
    #   {"type":"mekanism:sawing","input":{"count":1,"item":"minecraft:black_bed"},
    #    "main_output":{"count":3,"id":"minecraft:oak_planks"},"secondary_chance":1.0,
    #    "secondary_output":{"count":3,"id":"minecraft:black_wool"}}
    # The rewritten secondary keeps its sibling main_output and the recipe's own
    # secondary_chance, which is exactly what would be missing from a stray write.
    @{
        Name = 'mekanism:sawing (secondary_output, output.1)'
        RecipeId = 'mekanism:sawing/bed/black'
        Serializer = 'mekanism:sawing'
        RecipePath = 'data\mekanism\recipe\sawing\bed\black.json'
        Fields = '{"output.1.item":"minecraft:string","output.1.count":"2"}'
        Expect = @{
            'type' = 'mekanism:sawing'
            'input.item' = 'minecraft:black_bed'
            'input.count' = 1
            'main_output.id' = 'minecraft:oak_planks'
            'main_output.count' = 3
            'secondary_chance' = 1.0
            'secondary_output.id' = 'minecraft:string'
            'secondary_output.count' = 2
        }
    },
    # Ordered segment list: the sawmill output is
    #   ["stripped", "result", "strippingSecondaries.%d", "secondaryOutputs.%d"]
    # concatenated against the recipe JSON, because SawmillRecipeCategory draws
    # OUTPUT(stripped) only when the recipe carries "stripped", then OUTPUT(result), then one
    # per "strippingSecondaries" entry, then one per "secondaryOutputs" entry. This recipe
    # carries stripped, so its four OUTPUT slots are stripped=0, result=1,
    # strippingSecondaries.0=2 and secondaryOutputs.0=3. Addressing output.2 has to land in
    # "strippingSecondaries.0": if the concatenation wrongly skipped stripped it would land in
    # "secondaryOutputs.0", so both siblings are asserted as well.
    # data/immersiveengineering/recipe/sawmill/acacia_log.json is
    #   {"type":"immersiveengineering:sawmill","energy":1600,"input":{"item":"minecraft:acacia_log"},
    #    "result":{"count":6,"id":"minecraft:acacia_planks"},
    #    "stripped":{"id":"minecraft:stripped_acacia_log"},
    #    "strippingSecondaries":[{"tag":"c:dusts/wood"}],
    #    "secondaryOutputs":[{"tag":"c:dusts/wood"}]}
    @{
        Name = 'immersiveengineering:sawmill (stripped, output.2)'
        RecipeId = 'immersiveengineering:sawmill/acacia_log'
        Serializer = 'immersiveengineering:sawmill'
        RecipePath = 'data\immersiveengineering\recipe\sawmill\acacia_log.json'
        Fields = '{"output.2.item":"minecraft:oak_door","output.2.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:sawmill'
            'energy' = 1600
            'input.item' = 'minecraft:acacia_log'
            'result.id' = 'minecraft:acacia_planks'
            'result.count' = 6
            'stripped.id' = 'minecraft:stripped_acacia_log'
            'strippingSecondaries.0.id' = 'minecraft:oak_door'
            'strippingSecondaries.0.count' = 1
            'secondaryOutputs.0.tag' = 'c:dusts/wood'
        }
        Absent = @('strippingSecondaries.0.tag')
    },
    # The same declaration on a recipe without "stripped": the plain "stripped" segment
    # contributes no slot, the sequence shifts down by one, and OUTPUT ordinal 1 is
    # "strippingSecondaries.0" - on the recipe above ordinal 1 was "result". That is exactly
    # the ordinal-to-field ambiguity a positional declaration could not express.
    # data/immersiveengineering/recipe/sawmill/acacia_slab.json is
    #   {"type":"immersiveengineering:sawmill","energy":800,"input":{"item":"minecraft:acacia_planks"},
    #    "result":{"count":2,"id":"minecraft:acacia_slab"},
    #    "strippingSecondaries":[{"tag":"c:dusts/wood"}],"secondaryOutputs":[]}
    @{
        Name = 'immersiveengineering:sawmill (no stripped, output.1)'
        RecipeId = 'immersiveengineering:sawmill/acacia_slab'
        Serializer = 'immersiveengineering:sawmill'
        RecipePath = 'data\immersiveengineering\recipe\sawmill\acacia_slab.json'
        Fields = '{"output.1.item":"minecraft:oak_door","output.1.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:sawmill'
            'energy' = 800
            'input.item' = 'minecraft:acacia_planks'
            'result.id' = 'minecraft:acacia_slab'
            'result.count' = 2
            'strippingSecondaries.0.id' = 'minecraft:oak_door'
            'strippingSecondaries.0.count' = 1
        }
        Absent = @('strippingSecondaries.0.tag', 'stripped')
    },
    # The arc furnace segment list
    #   ["results.%d", "secondaries.%d.output", "slag"]
    # on a recipe that carries both "results" and "secondaries": output.0 is results[0] and
    # output.1 is secondaries[0].output (this recipe has no slag).
    # data/immersiveengineering/recipe/arcfurnace/raw_block_aluminum.json is
    #   {"type":"immersiveengineering:arc_furnace","additives":[],"energy":230400,
    #    "input":{"tag":"c:storage_blocks/raw_aluminum"},
    #    "results":[{"basePredicate":{"tag":"c:ingots/aluminum"},"count":13}],
    #    "secondaries":[{"chance":0.5,"output":{"tag":"c:ingots/aluminum"}}],"time":900}
    @{
        Name = 'immersiveengineering:arc_furnace (secondaries, output.1)'
        RecipeId = 'immersiveengineering:arcfurnace/raw_block_aluminum'
        Serializer = 'immersiveengineering:arc_furnace'
        RecipePath = 'data\immersiveengineering\recipe\arcfurnace\raw_block_aluminum.json'
        Fields = '{"output.1.item":"minecraft:diamond","output.1.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:arc_furnace'
            'energy' = 230400
            'time' = 900
            'input.tag' = 'c:storage_blocks/raw_aluminum'
            'results.0.basePredicate.tag' = 'c:ingots/aluminum'
            'results.0.count' = 13
            'secondaries.0.chance' = 0.5
            'secondaries.0.output.id' = 'minecraft:diamond'
            'secondaries.0.output.count' = 1
        }
        Absent = @('secondaries.0.output.tag', 'slag')
    },
    # The same segment list on a recipe that carries "results" and "slag", so output.1 is the
    # "slag" segment - the field a positional list could only have named by guessing, because
    # 30 of the 78 shipped recipes put a secondary at that ordinal instead.
    # data/immersiveengineering/recipe/arcfurnace/netherite_scrap.json is
    #   {"type":"immersiveengineering:arc_furnace","additives":[],"energy":512000,
    #    "input":{"item":"minecraft:ancient_debris"},
    #    "results":[{"count":2,"id":"minecraft:netherite_scrap"}],
    #    "slag":{"tag":"c:slag"},"time":100}
    @{
        Name = 'immersiveengineering:arc_furnace (slag, output.1)'
        RecipeId = 'immersiveengineering:arcfurnace/netherite_scrap'
        Serializer = 'immersiveengineering:arc_furnace'
        RecipePath = 'data\immersiveengineering\recipe\arcfurnace\netherite_scrap.json'
        Fields = '{"output.1.item":"minecraft:gravel","output.1.count":"3"}'
        Expect = @{
            'type' = 'immersiveengineering:arc_furnace'
            'energy' = 512000
            'time' = 100
            'input.item' = 'minecraft:ancient_debris'
            'results.0.id' = 'minecraft:netherite_scrap'
            'results.0.count' = 2
            'slag.id' = 'minecraft:gravel'
            'slag.count' = 3
        }
        Absent = @('slag.tag', 'secondaries')
    },
    # --- fluid and chemical slots (the A2 pages) -----------------------------
    #
    # A fluid or chemical slot is patched with the field names of its kind:
    #   <slot>.fluid    / <slot>.fluid_amount      (IngredientKind.FLUID)
    #   <slot>.chemical / <slot>.chemical_amount   (IngredientKind.CHEMICAL)
    # and the value written is the mod's own stack shape, which is NOT the same as the
    # item shape: Immersive Engineering decodes a fluid ingredient as
    # {"fluid":id,"amount":n} (SizedFluidIngredient.FLAT_CODEC) but a fluid result as
    # {"amount":n,"id":id} (FluidStack.OPTIONAL_CODEC), while Mekanism decodes a
    # chemical input as {"amount":n,"chemical":id} (ChemicalStackIngredient.CODEC) and
    # a chemical output as {"amount":n,"id":id} (ChemicalStack.MAP_CODEC).
    @{
        Name = 'immersiveengineering:refinery (fluid input0 + fluid result)'
        RecipeId = 'immersiveengineering:refinery/resin'
        Serializer = 'immersiveengineering:refinery'
        RecipePath = 'data\immersiveengineering\recipe\refinery\resin.json'
        # input.0 is input0, output is result. input1 is untouched.
        Fields = '{"input.0.fluid":"minecraft:water","input.0.fluid_amount":"500",' +
            '"output.fluid":"minecraft:lava","output.fluid_amount":"750"}'
        # The original file is
        #   {"type":"immersiveengineering:refinery","energy":240,
        #    "input0":{"amount":12,"tag":"c:acetaldehyde"},
        #    "input1":{"amount":8,"tag":"c:creosote"},
        #    "result":{"amount":8,"id":"immersiveengineering:phenolic_resin"}}
        # so input0 loses its tag, input1, energy and type survive, and result keeps the
        # result-side shape with the new fluid.
        Expect = @{
            'type' = 'immersiveengineering:refinery'
            'energy' = 240
            'input0.fluid' = 'minecraft:water'
            'input0.amount' = 500
            'input1.tag' = 'c:creosote'
            'input1.amount' = 8
            'result.id' = 'minecraft:lava'
            'result.amount' = 750
        }
        Absent = @('input0.tag', 'result.fluid')
    },
    # Mekanism's chemical page: two chemical inputs and a chemical output, all three
    # re-shapen in Mekanism's own keys. The untouched right_input and the item-shaped
    # siblings of other pages show the two styles side by side.
    # data/mekanism/recipe/chemical_infusing/sulfuric_acid.json is
    #   {"type":"mekanism:chemical_infusing","left_input":{"amount":1,"chemical":"mekanism:sulfur_trioxide"},
    #    "output":{"amount":1,"id":"mekanism:sulfuric_acid"},"right_input":{"amount":1,"chemical":"mekanism:water_vapor"}}
    @{
        Name = 'mekanism:chemical_infusing (chemical input0 + chemical output)'
        RecipeId = 'mekanism:chemical_infusing/sulfuric_acid'
        Serializer = 'mekanism:chemical_infusing'
        RecipePath = 'data\mekanism\recipe\chemical_infusing\sulfuric_acid.json'
        Fields = '{"input.0.chemical":"mekanism:hydrogen","input.0.chemical_amount":"5",' +
            '"output.chemical":"mekanism:chlorine","output.chemical_amount":"9"}'
        Expect = @{
            'type' = 'mekanism:chemical_infusing'
            'left_input.chemical' = 'mekanism:hydrogen'
            'left_input.amount' = 5
            'right_input.chemical' = 'mekanism:water_vapor'
            'right_input.amount' = 1
            'output.id' = 'mekanism:chlorine'
            'output.amount' = 9
        }
        Absent = @('output.chemical')
    },
    # One patch that mixes three kinds in a single declared page: the mixer's fluid input
    # is input ordinal 0, its item inputs are ordinals 1..N (the "inputs.%d" field), and
    # the result is a fluid. fluid -> {"fluid":…,"amount":…}, items -> {"item":…,"count":…},
    # result -> {"id":…,"amount":…}; inputs[1] and inputs[2] must survive verbatim.
    # data/immersiveengineering/recipe/mixer/concrete.json is
    #   {"type":"immersiveengineering:mixer","energy":3200,"fluid":{"amount":500,"tag":"minecraft:water"},
    #    "inputs":[{"basePredicate":{"tag":"c:sands"},"count":2},{"tag":"c:gravels"},{"tag":"c:clay"}],
    #    "result":{"amount":500,"id":"immersiveengineering:concrete"}}
    @{
        Name = 'immersiveengineering:mixer (fluid + item inputs, fluid result)'
        RecipeId = 'immersiveengineering:mixer/concrete'
        Serializer = 'immersiveengineering:mixer'
        RecipePath = 'data\immersiveengineering\recipe\mixer\concrete.json'
        Fields = '{"input.0.fluid":"minecraft:water","input.0.fluid_amount":"250",' +
            '"input.1.item":"minecraft:sand","input.1.count":"1",' +
            '"output.fluid":"minecraft:lava","output.fluid_amount":"1000"}'
        Expect = @{
            'type' = 'immersiveengineering:mixer'
            'energy' = 3200
            'fluid.fluid' = 'minecraft:water'
            'fluid.amount' = 250
            'inputs.0.item' = 'minecraft:sand'
            'inputs.0.count' = 1
            'inputs.1.tag' = 'c:gravels'
            'inputs.2.tag' = 'c:clay'
            'result.id' = 'minecraft:lava'
            'result.amount' = 1000
        }
        Absent = @('fluid.tag', 'inputs.0.basePredicate', 'result.fluid')
    },
    # --- mekanism:rotary: one serializer, two pages, opposite directions --------
    #
    # mekanism:condensentrating and mekanism:decondensentrating are two JEI pages of
    # one recipe type and one serializer, and every recipe is shown on both: the
    # category's own boolean decides whether the page reads the chemical input and
    # fluid output or the fluid input and chemical output. Both directions live in
    # the same recipe JSON, so a patch that only named "mekanism:rotary" could not
    # say which field list it means - the declaration is scoped to the page uid and
    # the serializer is refused on its own (see the probe below). These two cases
    # use the same recipe id and must therefore write different fields.
    # data/mekanism/recipe/rotary/uranium_oxide.json is
    #   {"type":"mekanism:rotary","chemical_input":{"amount":1,"chemical":"mekanism:uranium_oxide"},
    #    "chemical_output":{"amount":1,"id":"mekanism:uranium_oxide"},
    #    "fluid_input":{"amount":1,"tag":"c:uranium_oxide"},
    #    "fluid_output":{"amount":1,"id":"mekanism:uranium_oxide"}}
    # Only chemical_input/fluid_output may change here; the other direction's two
    # fields must survive verbatim with their original values.
    @{
        Name = 'mekanism:condensentrating (page-scoped: chemical_input + fluid_output)'
        RecipeId = 'mekanism:rotary/uranium_oxide'
        Serializer = 'mekanism:condensentrating'
        RecipePath = 'data\mekanism\recipe\rotary\uranium_oxide.json'
        Fields = '{"input.0.chemical":"mekanism:brine","input.0.chemical_amount":"5",' +
            '"output.fluid":"mekanism:brine","output.fluid_amount":"5"}'
        Expect = @{
            'type' = 'mekanism:rotary'
            'chemical_input.chemical' = 'mekanism:brine'
            'chemical_input.amount' = 5
            'fluid_output.id' = 'mekanism:brine'
            'fluid_output.amount' = 5
            'fluid_input.tag' = 'c:uranium_oxide'
            'fluid_input.amount' = 1
            'chemical_output.id' = 'mekanism:uranium_oxide'
            'chemical_output.amount' = 1
        }
    },
    # The mirror image: the decondensentrating declaration on a second rotary file writes
    # fluid_input and chemical_output and leaves the condensentrating direction alone.
    # (A different recipe id on purpose: one generated file, one patch - the two directions
    # must therefore be asserted on two files rather than twice on one.)
    # data/mekanism/recipe/rotary/brine.json is
    #   {"type":"mekanism:rotary","chemical_input":{"amount":1,"chemical":"mekanism:brine"},
    #    "chemical_output":{"amount":1,"id":"mekanism:brine"},
    #    "fluid_input":{"amount":1,"tag":"c:brine"},
    #    "fluid_output":{"amount":1,"id":"mekanism:brine"}}
    @{
        Name = 'mekanism:decondensentrating (page-scoped: fluid_input + chemical_output)'
        RecipeId = 'mekanism:rotary/brine'
        Serializer = 'mekanism:decondensentrating'
        RecipePath = 'data\mekanism\recipe\rotary\brine.json'
        Fields = '{"input.0.fluid":"minecraft:water","input.0.fluid_amount":"7",' +
            '"output.chemical":"mekanism:hydrogen","output.chemical_amount":"7"}'
        Expect = @{
            'type' = 'mekanism:rotary'
            'fluid_input.fluid' = 'minecraft:water'
            'fluid_input.amount' = 7
            'chemical_output.id' = 'mekanism:hydrogen'
            'chemical_output.amount' = 7
            'chemical_input.chemical' = 'mekanism:brine'
            'chemical_input.amount' = 1
            'fluid_output.id' = 'mekanism:brine'
            'fluid_output.amount' = 1
        }
        Absent = @('fluid_input.tag', 'chemical_output.chemical')
    },
    # --- mekanism:reaction: an optional item output and an optional chemical one -
    #
    # PressurizedReactionRecipeCategory adds its three INPUT slots unconditionally
    # (item_input, fluid_input, chemical_input) and then one OUTPUT slot per non-empty
    # entry of getOutputDefinition(), i.e. item_output when the item is present and
    # chemical_output when the chemical is. There is no fluid output at all. The
    # output list is therefore the ordered segment list ["item_output","chemical_output"]
    # and the two segments hold different kinds.
    # data/mekanism/recipe/reaction/substrate/ethene_oxygen.json is
    #   {"type":"mekanism:reaction","chemical_input":{"amount":10,"chemical":"mekanism:oxygen"},
    #    "duration":60,"energy_required":1000,"fluid_input":{"amount":50,"tag":"c:ethene"},
    #    "item_input":{"count":1,"item":"mekanism:substrate"},
    #    "item_output":{"count":1,"id":"mekanism:hdpe_pellet"}}
    # This recipe carries no chemical_output, so its only output ordinal is item_output.
    @{
        Name = 'mekanism:reaction (single item_output)'
        RecipeId = 'mekanism:reaction/substrate/ethene_oxygen'
        Serializer = 'mekanism:reaction'
        RecipePath = 'data\mekanism\recipe\reaction\substrate\ethene_oxygen.json'
        Fields = '{"input.2.chemical":"mekanism:hydrogen","input.2.chemical_amount":"5",' +
            '"output.item":"minecraft:diamond","output.count":"1"}'
        Expect = @{
            'type' = 'mekanism:reaction'
            'chemical_input.chemical' = 'mekanism:hydrogen'
            'chemical_input.amount' = 5
            'item_output.id' = 'minecraft:diamond'
            'item_output.count' = 1
            'fluid_input.tag' = 'c:ethene'
            'item_input.item' = 'mekanism:substrate'
            'duration' = 60
            'energy_required' = 1000
        }
        Absent = @('chemical_output', 'chemical_input.tag')
    },
    # The mixed-kind half of the segment list: with both outputs present, ordinal 0 is
    # item_output and ordinal 1 is chemical_output, so output.1 must be written as a
    # chemical while the sibling item output survives.
    # data/mekanism/recipe/reaction/coal_gasification/coals.json is
    #   {"type":"mekanism:reaction","chemical_input":{"amount":100,"chemical":"mekanism:oxygen"},
    #    "chemical_output":{"amount":100,"id":"mekanism:hydrogen"},"duration":100,
    #    "fluid_input":{"amount":100,"tag":"minecraft:water"},
    #    "item_input":{"count":1,"tag":"minecraft:coals"},
    #    "item_output":{"count":1,"id":"mekanism:dust_sulfur"}}
    @{
        Name = 'mekanism:reaction (output.1 = chemical_output)'
        RecipeId = 'mekanism:reaction/coal_gasification/coals'
        Serializer = 'mekanism:reaction'
        RecipePath = 'data\mekanism\recipe\reaction\coal_gasification\coals.json'
        Fields = '{"output.1.chemical":"mekanism:oxygen","output.1.chemical_amount":"9"}'
        Expect = @{
            'type' = 'mekanism:reaction'
            'chemical_output.id' = 'mekanism:oxygen'
            'chemical_output.amount' = 9
            'item_output.id' = 'mekanism:dust_sulfur'
            'item_output.count' = 1
            'item_input.tag' = 'minecraft:coals'
            'chemical_input.amount' = 100
            'duration' = 100
        }
        Absent = @('chemical_output.chemical')
    },
    # --- immersiveengineering:coke_oven: a fluid output written as a bare integer --
    #
    # CokeOvenRecipeCategory.setRecipe adds INPUT(input), then OUTPUT(result) when it is
    # non-empty, then OUTPUT(creosote) when creosoteOutput > 0, held as
    # new FluidStack(IEFluids.CREOSOTE.getStill(), creosoteOutput). "creosote" is a plain
    # required int in the codec, so the field value IS the amount and the fluid is implied
    # by the field name; the declaration fixes it to immersiveengineering:creosote.
    # data/immersiveengineering/recipe/cokeoven/charcoal.json is
    #   {"type":"immersiveengineering:coke_oven","creosote":250,
    #    "input":{"basePredicate":{"tag":"minecraft:logs_that_burn"},"count":8},
    #    "result":{"id":"minecraft:charcoal"},"time":3000}
    # The input keeps count 4 and the item output is not touched, so both the re-shaped
    # input and the untouched result/time are asserted together with the creosote number.
    @{
        Name = 'immersiveengineering:coke_oven (output.1 = bare creosote amount)'
        RecipeId = 'immersiveengineering:cokeoven/charcoal'
        Serializer = 'immersiveengineering:coke_oven'
        RecipePath = 'data\immersiveengineering\recipe\cokeoven\charcoal.json'
        Fields = '{"input.0.item":"minecraft:oak_log","input.0.count":"4",' +
            '"output.1.fluid":"immersiveengineering:creosote","output.1.fluid_amount":"300"}'
        Expect = @{
            'type' = 'immersiveengineering:coke_oven'
            'input.item' = 'minecraft:oak_log'
            'input.count' = 4
            'result.id' = 'minecraft:charcoal'
            'creosote' = 300
            'time' = 3000
        }
        Absent = @('input.basePredicate', 'creosote.id', 'creosote.amount')
    },
    # Output ordinal 0 is the item result; rewriting it must leave the creosote number
    # byte for byte.
    @{
        Name = 'immersiveengineering:coke_oven (output.0 = result)'
        RecipeId = 'immersiveengineering:cokeoven/coke'
        Serializer = 'immersiveengineering:coke_oven'
        RecipePath = 'data\immersiveengineering\recipe\cokeoven\coke.json'
        Fields = '{"output.0.item":"minecraft:coal","output.0.count":"2"}'
        Expect = @{
            'type' = 'immersiveengineering:coke_oven'
            'result.id' = 'minecraft:coal'
            'result.count' = 2
            'creosote' = 500
            'input.basePredicate.item' = 'minecraft:coal'
            'input.count' = 16
        }
        Absent = @('result.tag')
    },
    # --- mekanism:painting: a nested item input whose base is rewritten in place ----
    #
    # PaintingRecipeCategory adds INPUT(getItemInput()) as an item, INPUT(getChemicalInput())
    # as a chemical and OUTPUT(getOutputDefinition()) as an item, one slot each and always,
    # so the declaration is the plain pair plus the base-preserving item field. 160 of the
    # 176 shipped files write item_input as a nested neoforge:difference, 16 as a plain item
    # value. The nested node is declared with withPreservedIngredientBase, so only its
    # "base" becomes the chosen item: "type" and "subtracted" survive verbatim and the
    # written difference is again base-minus-subtracted (the page offers exactly those, so
    # the written recipe still contains the item the user picked). The
    # difference's "count" is that SizedIngredient's amount and is left untouched - all 176
    # shipped files write 1 there, and narrowing it would rewrite a number the page never
    # showed. The chemical input and the output keep their own shapes.
    # data/mekanism/recipe/painting/banner/black.json is
    #   {"type":"mekanism:painting","chemical_input":{"amount":256,"chemical":"mekanism:black"},
    #    "item_input":{"type":"neoforge:difference","base":{"tag":"mekanism:colorable/banners"},
    #                  "count":1,"subtracted":{"item":"minecraft:black_banner"}},
    #    "output":{"count":1,"id":"minecraft:black_banner"},"per_tick_usage":false}
    # minecraft:white_banner is a member of mekanism:colorable/banners and not the
    # subtracted one, so it is the first candidate the page offers for this slot.
    @{
        Name = 'mekanism:painting (nested item_input base rewritten, difference kept)'
        RecipeId = 'mekanism:painting/banner/black'
        Serializer = 'mekanism:painting'
        RecipePath = 'data\mekanism\recipe\painting\banner\black.json'
        Fields = '{"input.0.item":"minecraft:white_banner","input.0.count":"1",' +
            '"input.1.chemical":"mekanism:brine","input.1.chemical_amount":"128"}'
        Expect = @{
            'type' = 'mekanism:painting'
            'item_input.type' = 'neoforge:difference'
            'item_input.base.item' = 'minecraft:white_banner'
            'item_input.count' = 1
            'item_input.subtracted.item' = 'minecraft:black_banner'
            'chemical_input.chemical' = 'mekanism:brine'
            'chemical_input.amount' = 128
            'output.id' = 'minecraft:black_banner'
            'output.count' = 1
            'per_tick_usage' = $false
        }
        Absent = @('item_input.item', 'item_input.base.tag')
    },
    # Horse Powered's press writes a plain item pair, so this pins that the declaration
    # added for the page (MiscRecipeDeclarations) lines up with the shipped file: the input
    # is rewritten, the result and the untouched keys survive. The same page's fluid shape
    # is the second case below, because the category adds one OUTPUT slot in either branch
    # and which field that slot holds is the recipe's business.
    @{
        Name = 'horsepowered:pressing'
        RecipeId = 'horsepowered:pressing/allium_to_dye'
        Serializer = 'horsepowered:pressing'
        RecipePath = 'data\horsepowered\recipe\pressing\allium_to_dye.json'
        Fields = '{"input.0.item":"minecraft:cornflower","input.0.count":"1"}'
        Expect = @{
            'type' = 'horsepowered:pressing'
            'ingredient.item' = 'minecraft:cornflower'
            'result.id' = 'minecraft:magenta_dye'
            'result.count' = 4
        }
        Absent = @('ingredient.tag')
    },
    # The same page's fluid shape: OUTPUT ordinal 0 is "fluidResult" there, so the
    # ordered segment list has to resolve that field and write the fluid into it, while
    # the item "result" key must stay absent.
    @{
        Name = 'horsepowered:pressing (fluid shape)'
        RecipeId = 'horsepowered:pressing/ice_to_water'
        Serializer = 'horsepowered:pressing'
        RecipePath = 'data\horsepowered\recipe\pressing\ice_to_water.json'
        Fields = '{"input.0.item":"minecraft:packed_ice","input.0.count":"1","output.fluid":"minecraft:water","output.fluid_amount":"500"}'
        Expect = @{
            'type' = 'horsepowered:pressing'
            'ingredient.item' = 'minecraft:packed_ice'
            'fluidResult.id' = 'minecraft:water'
            'fluidResult.amount' = 500
        }
        Absent = @('result', 'ingredient.tag')
    },
    # Immersive Engineering's squeezer draws an item OUTPUT slot unconditionally but
    # leaves it empty on its 5 fluid recipes, so those pages draw two output slots and
    # the declaration names both by ordinal: 0 is "result" (empty there), 1 is "fluid".
    # data/immersiveengineering/recipe/squeezer/melon_seeds.json is
    #   {"type":"immersiveengineering:squeezer","energy":6400,
    #    "fluid":{"amount":20,"id":"immersiveengineering:plantoil"},
    #    "input":{"item":"minecraft:melon_seeds"}}
    # and .../graphite_dust.json carries "result" instead, which is the one-output shape
    # where ordinal 0 is that item. The two cases below pin both: the fluid one writes
    # "fluid" and leaves "result" absent, the item one does the reverse, and the
    # undeclared "energy" survives in both.
    @{
        Name = 'immersiveengineering:squeezer (fluid shape)'
        RecipeId = 'immersiveengineering:squeezer/melon_seeds'
        Serializer = 'immersiveengineering:squeezer'
        RecipePath = 'data\immersiveengineering\recipe\squeezer\melon_seeds.json'
        Fields = '{"input.0.item":"minecraft:pumpkin_seeds","input.0.count":"1","output.1.fluid":"immersiveengineering:plantoil","output.1.fluid_amount":"40"}'
        Expect = @{
            'type' = 'immersiveengineering:squeezer'
            'energy' = 6400
            'input.item' = 'minecraft:pumpkin_seeds'
            'fluid.id' = 'immersiveengineering:plantoil'
            'fluid.amount' = 40
        }
        Absent = @('result', 'input.tag', 'input.basePredicate')
    },
    @{
        Name = 'immersiveengineering:squeezer (item shape)'
        RecipeId = 'immersiveengineering:squeezer/graphite_dust'
        Serializer = 'immersiveengineering:squeezer'
        RecipePath = 'data\immersiveengineering\recipe\squeezer\graphite_dust.json'
        Fields = '{"input.0.item":"minecraft:coal","input.0.count":"1","output.item":"immersiveengineering:dust_hop_graphite","output.count":"2"}'
        Expect = @{
            'type' = 'immersiveengineering:squeezer'
            'energy' = 19200
            'input.item' = 'minecraft:coal'
            'input.count' = 1
            'result.id' = 'immersiveengineering:dust_hop_graphite'
            'result.count' = 2
        }
        Absent = @('fluid', 'input.tag', 'input.basePredicate')
    },
    # The same shape one page over: the fermenter always writes "fluid", and honey.json
    # is the only recipe that also fills the always-created item OUTPUT slot, so it is
    # the one whose two outputs are both non-empty. Its case below pins that both
    # ordinals resolve to their own field in one patch.
    @{
        Name = 'immersiveengineering:fermenter (fluid shape)'
        RecipeId = 'immersiveengineering:fermenter/potato'
        Serializer = 'immersiveengineering:fermenter'
        RecipePath = 'data\immersiveengineering\recipe\fermenter\potato.json'
        Fields = '{"input.0.item":"minecraft:baked_potato","input.0.count":"1","output.1.fluid":"immersiveengineering:ethanol","output.1.fluid_amount":"160"}'
        Expect = @{
            'type' = 'immersiveengineering:fermenter'
            'energy' = 6400
            'input.item' = 'minecraft:baked_potato'
            'fluid.id' = 'immersiveengineering:ethanol'
            'fluid.amount' = 160
        }
        Absent = @('result', 'input.tag')
    },
    @{
        Name = 'immersiveengineering:fermenter (two outputs)'
        RecipeId = 'immersiveengineering:fermenter/honey'
        Serializer = 'immersiveengineering:fermenter'
        RecipePath = 'data\immersiveengineering\recipe\fermenter\honey.json'
        Fields = '{"input.0.item":"minecraft:honey_block","input.0.count":"1","output.0.item":"minecraft:glass_bottle","output.0.count":"1","output.1.fluid":"immersiveengineering:ethanol","output.1.fluid_amount":"300"}'
        Expect = @{
            'type' = 'immersiveengineering:fermenter'
            'energy' = 6400
            'input.item' = 'minecraft:honey_block'
            'input.count' = 1
            'result.id' = 'minecraft:glass_bottle'
            'result.count' = 1
            'fluid.id' = 'immersiveengineering:ethanol'
            'fluid.amount' = 300
        }
        Absent = @('input.tag')
    },
    # Two fluid tags, because a fluid tag is not an item tag with a different key. The
    # item case further down writes the tag alone, which is the shape every mod's plain
    # Ingredient reads; a fluid tag is a *sized* fluid ingredient, so the amount is part
    # of the value and omitting it does not fail - SizedFluidIngredient.FLAT_CODEC reads
    # "amount" with the bucket volume as its default, so a recipe that consumed 12 mB
    # would silently consume 1000. And Create's fluid ingredient dispatches on an
    # explicit type name whose single-fluid value (neoforge:single) does not accept a
    # tag: the shipped files write neoforge:tag. Both cases below are on pages whose
    # fluid field really is a SizedFluidIngredient.
    # data/immersiveengineering/recipe/refinery/biodiesel.json is
    #   {"type":"immersiveengineering:refinery","catalyst":{"tag":"c:dusts/saltpeter"},
    #    "energy":80,"input0":{"amount":8,"tag":"c:plantoil"},
    #    "input1":{"amount":8,"tag":"c:ethanol"},
    #    "result":{"amount":16,"id":"immersiveengineering:biodiesel"}}
    # - the same page as the fluid-id case above, on a recipe that case does not use.
    @{
        Name = 'immersiveengineering:refinery (fluid tag input)'
        RecipeId = 'immersiveengineering:refinery/biodiesel'
        Serializer = 'immersiveengineering:refinery'
        RecipePath = 'data\immersiveengineering\recipe\refinery\biodiesel.json'
        Fields = '{"input.0.fluid":"#minecraft:water","input.0.fluid_amount":"20"}'
        Expect = @{
            'type' = 'immersiveengineering:refinery'
            'energy' = 80
            'catalyst.tag' = 'c:dusts/saltpeter'
            'input0.tag' = 'minecraft:water'
            'input0.amount' = 20
            'input1.tag' = 'c:ethanol'
            'input1.amount' = 8
            'result.id' = 'immersiveengineering:biodiesel'
        }
        Absent = @('input0.fluid', 'input0.id')
    },
    # data/create/recipe/mixing/chocolate.json is
    #   {"type":"create:mixing","heat_requirement":"heated",
    #    "ingredients":[{"item":"minecraft:sugar"},{"item":"minecraft:cocoa_beans"},
    #                   {"type":"neoforge:tag","amount":250,"tag":"c:milk"}],
    #    "results":[{"amount":250,"id":"create:chocolate"}]}
    # - the two item ingredients condense to two slots, so input.2 is the fluid one.
    @{
        Name = 'create:mixing (fluid tag input, typed)'
        RecipeId = 'create:mixing/chocolate'
        Serializer = 'create:mixing'
        RecipePath = 'data\create\recipe\mixing\chocolate.json'
        Fields = '{"input.2.fluid":"#minecraft:water","input.2.fluid_amount":"250"}'
        Expect = @{
            'type' = 'create:mixing'
            'heat_requirement' = 'heated'
            'ingredients.0.item' = 'minecraft:sugar'
            'ingredients.1.item' = 'minecraft:cocoa_beans'
            'ingredients.2.type' = 'neoforge:tag'
            'ingredients.2.tag' = 'minecraft:water'
            'ingredients.2.amount' = 250
            'results.0.id' = 'create:chocolate'
        }
        Absent = @('ingredients.2.fluid')
    },
    # Silent Gear's alloy pages. Its JEI plugin pushes the bare recipe objects instead
    # of RecipeHolders, so the client resolves the displayed object to the manager's
    # recipe by identity before it models the page; the id it finds is the file below.
    # The ingredient array length is the page's input count, and the result is written
    # in the mod's own result shape, {"item":…,"count":…} - not the "id" form the other
    # declared pages use - which is what this case pins.
    # data/silentgear/recipe/alloying/metal/azure_electrum_ingot.json is
    #   {"type":"silentgear:alloy_making/metal",
    #    "ingredients":[{"tag":"c:storage_blocks/azure_silver"},{"tag":"c:ingots/gold"},
    #                   {"tag":"c:ingots/gold"},{"tag":"c:ender_pearls"}],
    #    "result":{"count":3,"item":"silentgear:azure_electrum_ingot"}}
    @{
        Name = 'silentgear:alloy_making/metal (ingredients.%d, item-shaped result)'
        RecipeId = 'silentgear:alloying/metal/azure_electrum_ingot'
        Serializer = 'silentgear:alloy_making/metal'
        RecipePath = 'data\silentgear\recipe\alloying\metal\azure_electrum_ingot.json'
        Fields = '{"input.0.item":"minecraft:gold_block","input.0.count":"1","output.item":"silentgear:blaze_gold_ingot","output.count":"2"}'
        Expect = @{
            'type' = 'silentgear:alloy_making/metal'
            'ingredients.0.item' = 'minecraft:gold_block'
            'ingredients.1.tag' = 'c:ingots/gold'
            'ingredients.2.tag' = 'c:ingots/gold'
            'ingredients.3.tag' = 'c:ender_pearls'
            'result.item' = 'silentgear:blaze_gold_ingot'
            'result.count' = 2
        }
        Absent = @('ingredients.0.tag', 'result.id')
    },
    # data/silentgear/recipe/azure_electrum_dust.json is
    #   {"type":"silentgear:tool_action","damage_to_tool":1,
    #    "ingredient":{"tag":"c:ingots/azure_electrum"},
    #    "result":{"count":1,"id":"silentgear:azure_electrum_dust"},
    #    "sound":{"pitch":1.0,"pitch_deviation":0.1,
    #             "sound":"silentgear:block.silentgear.stone_anvil.hammer","volume":1.0},
    #    "tool":{"tag":"c:tools/hammer"}}
    # The category adds INPUT(tool) before INPUT(ingredient), so input.0 is the tool and
    # input.1 the item being worked; "damage_to_tool" and "sound" are undeclared keys
    # that must survive.
    @{
        Name = 'silentgear:tool_action (tool then ingredient)'
        RecipeId = 'silentgear:azure_electrum_dust'
        Serializer = 'silentgear:tool_action'
        RecipePath = 'data\silentgear\recipe\azure_electrum_dust.json'
        Fields = '{"input.0.item":"minecraft:iron_ingot","input.0.count":"1","input.1.item":"minecraft:gold_ingot","input.1.count":"1","output.item":"minecraft:redstone","output.count":"4"}'
        Expect = @{
            'type' = 'silentgear:tool_action'
            'damage_to_tool' = 1
            'tool.item' = 'minecraft:iron_ingot'
            'ingredient.item' = 'minecraft:gold_ingot'
            'result.id' = 'minecraft:redstone'
            'result.count' = 4
            'sound.sound' = 'silentgear:block.silentgear.stone_anvil.hammer'
        }
        Absent = @('tool.tag', 'ingredient.tag')
    },
    # The gear_crafting page displays two recipe types (70 files of
    # silentgear:gear_crafting plus 43 of silentgear:compound_part = the page's 113), and
    # the ingredient entries are Silent Gear's own ingredient types, registered through
    # NeoForge's IngredientType dispatch, so their codec falls back to the vanilla
    # single/tag form and the declared item shape decodes. This case edits two entries of
    # one recipe and leaves the middle one - a {"type":"silentgear:part_type"} entry -
    # verbatim, which is what its own Absent/Expect pair pins.
    # data/silentgear/recipe/gear/arrow.json is
    #   {"type":"silentgear:gear_crafting","category":"equipment",
    #    "ingredients":[{"item":"silentgear:arrow_heads"},
    #                   {"type":"silentgear:part_type","part_type":"silentgear:rod"},
    #                   {"type":"silentgear:part_type","part_type":"silentgear:fletching"}],
    #    "result":{"count":1,"id":"silentgear:arrow"}}
    @{
        Name = 'silentgear:gear_crafting (a custom ingredient type survives)'
        RecipeId = 'silentgear:gear/arrow'
        Serializer = 'silentgear:gear_crafting'
        RecipePath = 'data\silentgear\recipe\gear\arrow.json'
        Fields = '{"input.0.item":"minecraft:flint","input.0.count":"1","input.2.item":"minecraft:feather","input.2.count":"1"}'
        Expect = @{
            'type' = 'silentgear:gear_crafting'
            'category' = 'equipment'
            'ingredients.0.item' = 'minecraft:flint'
            'ingredients.1.type' = 'silentgear:part_type'
            'ingredients.1.part_type' = 'silentgear:rod'
            'ingredients.2.item' = 'minecraft:feather'
            'result.id' = 'silentgear:arrow'
            'result.count' = 1
        }
        Absent = @('ingredients.0.type', 'ingredients.2.part_type')
    },
    # data/silentgear/recipe/gear/arrow_head.json is the other type of the same page:
    #   {"type":"silentgear:compound_part","category":"equipment",
    #    "ingredients":[{"type":"silentgear:blueprint","gear_type":"silentgear:arrow",
    #                    "part_type":"silentgear:main"},
    #                   {"type":"silentgear:material","gear_type":"silentgear:arrow",
    #                    "part_type":"silentgear:main"}],
    #    "result":{"count":1,"id":"silentgear:arrow_heads"}}
    @{
        Name = 'silentgear:compound_part (blueprint slot narrowed to an item)'
        RecipeId = 'silentgear:gear/arrow_head'
        Serializer = 'silentgear:compound_part'
        RecipePath = 'data\silentgear\recipe\gear\arrow_head.json'
        Fields = '{"input.0.item":"minecraft:paper","input.0.count":"1"}'
        Expect = @{
            'type' = 'silentgear:compound_part'
            'category' = 'equipment'
            'ingredients.0.item' = 'minecraft:paper'
            'ingredients.1.type' = 'silentgear:material'
            'ingredients.1.gear_type' = 'silentgear:arrow'
            'result.id' = 'silentgear:arrow_heads'
        }
        Absent = @('ingredients.0.type', 'ingredients.0.part_type')
    },
    # The salvaging page draws three recipe types; two of them are declared. The plain
    # "salvaging" one has an ingredient and a results array, one output slot per entry.
    # data/silentgear/recipe/salvaging/bow.json is
    #   {"type":"silentgear:salvaging","ingredient":{"item":"minecraft:bow"},
    #    "results":[{"count":3,"id":"minecraft:string"},{"count":3,"id":"minecraft:stick"}]}
    # so this patch rewrites the input and the FIRST result while the second keeps both of
    # its keys.
    @{
        Name = 'silentgear:salvaging (results.%d)'
        RecipeId = 'silentgear:salvaging/bow'
        Serializer = 'silentgear:salvaging'
        RecipePath = 'data\silentgear\recipe\salvaging\bow.json'
        Fields = '{"input.0.item":"minecraft:crossbow","input.0.count":"1","output.0.item":"minecraft:flint","output.0.count":"2"}'
        Expect = @{
            'type' = 'silentgear:salvaging'
            'ingredient.item' = 'minecraft:crossbow'
            'results.0.id' = 'minecraft:flint'
            'results.0.count' = 2
            'results.1.id' = 'minecraft:stick'
            'results.1.count' = 3
        }
        Absent = @('ingredient.tag', 'results.0.tag')
    },
    # data/silentgear/recipe/salvaging/gear/axe.json is
    #   {"type":"silentgear:salvaging/gear","ingredient":{"item":"silentgear:axe"}}
    # - the type whose page draws ONE slot and no output at all
    # (GearSalvagingRecipe.getPossibleResultsForDisplay() is an empty list), so it is
    # declared through SilentGearSlotSequences.SalvageGear and this patch carries no
    # output key at all.
    @{
        Name = 'silentgear:salvaging/gear (input only, no output slot)'
        RecipeId = 'silentgear:salvaging/gear/axe'
        Serializer = 'silentgear:salvaging/gear'
        RecipePath = 'data\silentgear\recipe\salvaging\gear\axe.json'
        Fields = '{"input.0.item":"minecraft:diamond_axe","input.0.count":"1"}'
        Expect = @{
            'type' = 'silentgear:salvaging/gear'
            'ingredient.item' = 'minecraft:diamond_axe'
        }
        Absent = @('ingredient.tag', 'results')
    },
    # The other half of the clear rule: an element a repeatable pattern named IS removed by
    # the clear gesture, which is how a variable-arity list shrinks. The blueprint page's
    # inputs are "inputs.%d", so clearing input.2 of this three-input recipe drops the last
    # entry and leaves the two before it untouched.
    # data/immersiveengineering/recipe/blueprint/bullet_explosive.json is
    #   {"type":"immersiveengineering:blueprint","category":"specialBullet",
    #    "inputs":[{"basePredicate":{"item":"immersiveengineering:empty_casing"},"count":4},
    #               {"tag":"c:gunpowders"},{"item":"minecraft:tnt"}],
    #    "result":{"count":4,"id":"immersiveengineering:bullet_he"}}
    @{
        Name = 'immersiveengineering:blueprint (a cleared repeat element is removed)'
        RecipeId = 'immersiveengineering:blueprint/bullet_explosive'
        Serializer = 'immersiveengineering:blueprint'
        RecipePath = 'data\immersiveengineering\recipe\blueprint\bullet_explosive.json'
        Fields = '{"input.2.item":"minecraft:air","input.2.count":"0"}'
        Expect = @{
            'type' = 'immersiveengineering:blueprint'
            'category' = 'specialBullet'
            'inputs.0.basePredicate.item' = 'immersiveengineering:empty_casing'
            'inputs.0.count' = 4
            'inputs.1.tag' = 'c:gunpowders'
            'result.id' = 'immersiveengineering:bullet_he'
        }
        Absent = @('inputs.2.item', 'inputs.2.tag')
    },
    # data/mekanism/recipe/energy_conversion/redstone.json is
    #   {"type":"mekanism:energy_conversion","input":{"count":1,"tag":"c:dusts/redstone"},
    #    "output":10000}
    # The page draws one slot and shows the energy as text beside it, so the declaration
    # has no output field at all (SingleItemInputSequence is the form for that) and the
    # bare "output" amount must survive the edit untouched.
    @{
        Name = 'mekanism:energy_conversion (input only, energy survives)'
        RecipeId = 'mekanism:energy_conversion/redstone'
        Serializer = 'mekanism:energy_conversion'
        RecipePath = 'data\mekanism\recipe\energy_conversion\redstone.json'
        Fields = '{"input.0.item":"minecraft:redstone","input.0.count":"1"}'
        Expect = @{
            'type' = 'mekanism:energy_conversion'
            'input.item' = 'minecraft:redstone'
            'input.count' = 1
            'output' = 10000
        }
        Absent = @('input.tag')
    },
    # Create's spout page is declared through CreateSpoutSlotSequence: the item entry and
    # the fluid entry can sit at any index of the "ingredients" array, so the sequence
    # resolves each slot's path by the entry's own kind (a fluid entry is one whose "type"
    # names a registered fluid ingredient type) and cross-checks the item half against the
    # Ingredient objects the mod parsed.
    # data/create/recipe/filling/compat/immersiveengineering/treated_wood_in_spout.json is
    #   {"neoforge:conditions":[{"type":"neoforge:mod_loaded","modid":"immersiveengineering"}],
    #    "type":"create:filling",
    #    "ingredients":[{"tag":"minecraft:planks"},
    #                   {"type":"neoforge:tag","amount":125,"tag":"c:creosote"}],
    #    "results":[{"id":"immersiveengineering:treated_wood_horizontal"}]}
    # The fluid is rewritten in Create's sized shape (which needs its explicit "type"), the
    # item entry loses its tag, and the condition survives (that copy is the E14 fix).
    @{
        Name = 'create:filling (item + fluid by shape, condition survives)'
        RecipeId = 'create:filling/compat/immersiveengineering/treated_wood_in_spout'
        Serializer = 'create:filling'
        RecipePath = 'data\create\recipe\filling\compat\immersiveengineering\treated_wood_in_spout.json'
        Fields = '{"input.0.item":"minecraft:oak_planks","input.0.count":"1","input.1.fluid":"minecraft:water","input.1.fluid_amount":"250","output.item":"minecraft:stripped_oak_log","output.count":"1"}'
        Expect = @{
            'type' = 'create:filling'
            'ingredients.0.item' = 'minecraft:oak_planks'
            'ingredients.1.type' = 'neoforge:single'
            'ingredients.1.fluid' = 'minecraft:water'
            'ingredients.1.amount' = 250
            'results.0.id' = 'minecraft:stripped_oak_log'
            'results.0.count' = 1
            'neoforge:conditions.0.modid' = 'immersiveengineering'
        }
        Absent = @('ingredients.0.tag', 'ingredients.1.tag')
    },
    # Cobblemon's campfire pot page. Its shapeless type is declared through
    # CobblemonCampfirePotSlotSequence because the page draws one INPUT slot per ingredient
    # entry and then one PREVIEW slot per matching example seasoning - and those previews
    # have no field of their own (the recipe names processors and a tag, not the seasonings).
    # A field list would name them too, and a drop there would append an ingredient.
    # data/cobblemon/recipe/campfire_pot/antidote_in_campfire_pot.json is
    #   {"type":"cobblemon:cooking_pot_shapeless","category":"medicines",
    #    "ingredients":[{"item":"minecraft:glass_bottle"},{"item":"cobblemon:pecha_berry"}],
    #    "result":{"id":"cobblemon:antidote"},"seasoningProcessors":[],
    #    "seasoningTag":"cobblemon:empty"}
    @{
        Name = 'cobblemon:campfire_pot_recipe (ingredients by sequence)'
        RecipeId = 'cobblemon:campfire_pot/antidote_in_campfire_pot'
        Serializer = 'cobblemon:cooking_pot_shapeless'
        RecipePath = 'data\cobblemon\recipe\campfire_pot\antidote_in_campfire_pot.json'
        Fields = '{"input.0.item":"minecraft:bucket","input.0.count":"1","output.item":"minecraft:potion","output.count":"1"}'
        Expect = @{
            'type' = 'cobblemon:cooking_pot_shapeless'
            'category' = 'medicines'
            'ingredients.0.item' = 'minecraft:bucket'
            'ingredients.1.item' = 'cobblemon:pecha_berry'
            'result.id' = 'minecraft:potion'
            'result.count' = 1
            'seasoningTag' = 'cobblemon:empty'
        }
        Absent = @('ingredients.0.tag')
    },
    # Create's mechanical crafting page. The category draws a dense grid of INPUT slots
    # over the pattern's cells - one slot per cell in reading order, blanks included - so
    # the k-th slot is the k-th cell of the flattened pattern. The JSON names ingredients
    # per pattern CHARACTER ("key.<letter>"), and a blank cell has no field at all: the
    # sequence declares no path for it and a patch addressed to it is refused (the probe
    # below proves that). Two cells carrying the same letter resolve to the same field -
    # that is the recipe's own structure - and this case pins it on wand_of_symmetry's "G",
    # whose three cells are ordinals 1, 3 and 5 of the 3x5 pattern below, so this patch on
    # input.1 also rewrites the cells at input.3 and input.5.
    # data/create/recipe/mechanical_crafting/wand_of_symmetry.json is
    #   {"type":"create:mechanical_crafting","accept_mirrored":true,"category":"misc",
    #    "key":{"B":{"tag":"c:ingots/brass"},"E":{"tag":"c:ender_pearls"},
    #           "G":{"tag":"c:glass_blocks"},"O":{"tag":"c:obsidians"},
    #           "P":{"item":"create:precision_mechanism"}},
    #    "pattern":[" G ","GEG"," P "," B "," O "],
    #    "result":{"count":1,"id":"create:wand_of_symmetry"}}
    @{
        Name = 'create:mechanical_crafting (a shared key letter) [input.1 = letter G]'
        RecipeId = 'create:mechanical_crafting/wand_of_symmetry'
        Serializer = 'create:mechanical_crafting'
        RecipePath = 'data\create\recipe\mechanical_crafting\wand_of_symmetry.json'
        Fields = '{"input.1.item":"minecraft:iron_ingot","input.1.count":"1"}'
        Expect = @{
            'type' = 'create:mechanical_crafting'
            'accept_mirrored' = $true
            'category' = 'misc'
            'key.G.item' = 'minecraft:iron_ingot'
            'key.E.tag' = 'c:ender_pearls'
            'key.O.tag' = 'c:obsidians'
            'key.B.tag' = 'c:ingots/brass'
            'key.P.item' = 'create:precision_mechanism'
            'result.id' = 'create:wand_of_symmetry'
            'result.count' = 1
        }
        Absent = @('key.G.tag')
    },
    # Create's sequenced assembly page. Its slots follow the recipe's "sequence" steps, so
    # every input sits at a per-step path rather than at a positionally computable field:
    # ordinal 0 is the base "ingredient", and each step adds one only if its JEI
    # sub-category draws one - "create:deploying" draws the step's SECOND item entry (the
    # item the deployer holds; item[0] is the incomplete item the step works on) and
    # "create:filling" draws its first fluid entry, while "create:pressing" and
    # "create:cutting" draw none at all. That is CreateSequencedAssemblySlotSequence's
    # derivation; the client reported the resulting shapes as 4 / 3 / 2 inputs over these
    # three files.
    # data/create/recipe/sequenced_assembly/precision_mechanism.json is
    #   {"type":"create:sequenced_assembly","ingredient":{"tag":"c:plates/gold"},"loops":5,
    #    "transitional_item":{"id":"create:incomplete_precision_mechanism"},
    #    "results":[{"chance":120.0,"id":"create:precision_mechanism"},
    #               {"chance":8.0,"id":"create:golden_sheet"}, …],
    #    "sequence":[{"type":"create:deploying","ingredients":[
    #                    {"item":"create:incomplete_precision_mechanism"},
    #                    {"item":"create:cogwheel"}],"results":[…]},
    #                {…"create:large_cogwheel"…}, {…"tag":"c:nuggets/iron"…}]}
    # input.1 is the first step's held item. The written output replaces results.0
    # wholesale - the accepted limitation of every declared Create "results" entry (see
    # CreateRecipeDeclarations' class comment) - which the Absent list pins here, while the
    # sibling entry keeps both its id and its chance.
    @{
        Name = 'create:sequenced_assembly (a deploying step writes its held item and the output)'
        RecipeId = 'create:sequenced_assembly/precision_mechanism'
        Serializer = 'create:sequenced_assembly'
        RecipePath = 'data\create\recipe\sequenced_assembly\precision_mechanism.json'
        Fields = '{"input.1.item":"minecraft:iron_ingot","input.1.count":"1","output.item":"minecraft:gold_block","output.count":"1"}'
        Expect = @{
            'type' = 'create:sequenced_assembly'
            'ingredient.tag' = 'c:plates/gold'
            'loops' = 5
            'transitional_item.id' = 'create:incomplete_precision_mechanism'
            'sequence.0.ingredients.0.item' = 'create:incomplete_precision_mechanism'
            'sequence.0.ingredients.1.item' = 'minecraft:iron_ingot'
            'sequence.1.ingredients.1.item' = 'create:large_cogwheel'
            'sequence.2.ingredients.1.tag' = 'c:nuggets/iron'
            'results.0.id' = 'minecraft:gold_block'
            'results.1.id' = 'create:golden_sheet'
            'results.1.chance' = 8.0
        }
        Absent = @('results.0.chance')
    },
    # A filling step's slot is the fluid entry of its own "ingredients" array, and the mod's
    # fluid ingredient codec rejects a bare {"fluid":…,"amount":…} - the "type" is required -
    # so the declaration writes this page with ModdedJsonStyle.TYPED_FLUID and a written node
    # replaces the entry wholesale. This case is what pins that: the type is still there
    # afterwards, next to the new id and amount.
    # data/create/recipe/sequenced_assembly/sturdy_sheet.json is
    #   {"type":"create:sequenced_assembly","ingredient":{"tag":"c:dusts/obsidian"},
    #    "transitional_item":{"id":"create:unprocessed_obsidian_sheet"},
    #    "results":[{"id":"create:sturdy_sheet"}],
    #    "sequence":[{"type":"create:filling","ingredients":[
    #                    {"item":"create:unprocessed_obsidian_sheet"},
    #                    {"amount":500,"fluid":"minecraft:lava","type":"neoforge:single"}],
    #                  "results":[…]},
    #                {"type":"create:pressing",…},{"type":"create:pressing",…}]}
    @{
        Name = 'create:sequenced_assembly (a filling step writes its fluid)'
        RecipeId = 'create:sequenced_assembly/sturdy_sheet'
        Serializer = 'create:sequenced_assembly'
        RecipePath = 'data\create\recipe\sequenced_assembly\sturdy_sheet.json'
        Fields = '{"input.1.fluid":"minecraft:water","input.1.fluid_amount":"1000"}'
        Expect = @{
            'type' = 'create:sequenced_assembly'
            'ingredient.tag' = 'c:dusts/obsidian'
            'transitional_item.id' = 'create:unprocessed_obsidian_sheet'
            'sequence.0.ingredients.0.item' = 'create:unprocessed_obsidian_sheet'
            'sequence.0.ingredients.1.fluid' = 'minecraft:water'
            'sequence.0.ingredients.1.amount' = 1000
            'sequence.0.ingredients.1.type' = 'neoforge:single'
            'sequence.1.type' = 'create:pressing'
            'results.0.id' = 'create:sturdy_sheet'
        }
    },
    # Immersive Engineering's cloche is declared with a recipe-derived slot sequence
    # (ImmersiveEngineeringSlotSequences.Cloche), so this case pins the two things a
    # field list could not do. The page's two inputs are the seed and the fluid the
    # cloche consumes; the fluid is a plain FluidIngredient whose JSON key is "fluid"
    # (an id string), not the "id" a FluidStack uses - and the file below is one of the
    # 38 that omit it entirely, so its "fluid" key is created by the write.
    # data/immersiveengineering/recipe/cloche/wheat.json is
    #   {"type":"immersiveengineering:cloche","input":{"item":"minecraft:wheat_seeds"},
    #    "render":{"type":"immersiveengineering:crop","block":"minecraft:wheat"},
    #    "results":[{"count":2,"id":"minecraft:wheat"},
    #               {"chance":0.25,"output":{"id":"minecraft:wheat_seeds"}}],
    #    "soil":{"item":"minecraft:dirt"},"time":640}
    # The second output is the chance-wrapped shape: the edit has to land in its nested
    # "output" object, so "chance" (and any "conditions") survives and no sibling "id"
    # appears next to it. The soil and render blocks are CATALYST / undeclared keys.
    @{
        Name = 'immersiveengineering:cloche (nested chance output)'
        RecipeId = 'immersiveengineering:cloche/wheat'
        Serializer = 'immersiveengineering:cloche'
        RecipePath = 'data\immersiveengineering\recipe\cloche\wheat.json'
        Fields = '{"input.0.item":"minecraft:pumpkin_seeds","input.0.count":"1","input.1.fluid":"minecraft:water","input.1.fluid_amount":"1000","output.0.item":"minecraft:bread","output.0.count":"3","output.1.item":"minecraft:wheat_seeds","output.1.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:cloche'
            'time' = 640
            'input.item' = 'minecraft:pumpkin_seeds'
            'fluid.fluid' = 'minecraft:water'
            'fluid.amount' = 1000
            'results.0.id' = 'minecraft:bread'
            'results.0.count' = 3
            'results.1.output.id' = 'minecraft:wheat_seeds'
            'results.1.output.count' = 1
            'results.1.chance' = 0.25
            'soil.item' = 'minecraft:dirt'
            'render.type' = 'immersiveengineering:crop'
        }
        Absent = @('results.1.id', 'results.1.output.tag')
    },
    # The bottling machine's item input is spelled "input" on this file and "inputs" on
    # the next one, which is why the page needed a sequence: the two cases pin that both
    # spellings resolve to the same page shape (one item slot plus the fluid, then one
    # output slot here and two there). The fluid is a SizedFluidIngredient, so its
    # written node carries the amount beside the id - and the file's own "tag" form is
    # replaced by the id form, exactly like every other declared IE fluid input.
    # data/immersiveengineering/recipe/bottling/black_concrete_powder.json is
    #   {"type":"immersiveengineering:bottling_machine",
    #    "fluid":{"amount":125,"tag":"minecraft:water"},
    #    "input":{"item":"minecraft:black_concrete_powder"},
    #    "results":[{"id":"minecraft:black_concrete"}]}
    @{
        Name = 'immersiveengineering:bottling_machine (input, single item)'
        RecipeId = 'immersiveengineering:bottling/black_concrete_powder'
        Serializer = 'immersiveengineering:bottling_machine'
        RecipePath = 'data\immersiveengineering\recipe\bottling\black_concrete_powder.json'
        Fields = '{"input.0.item":"minecraft:white_concrete_powder","input.0.count":"1","input.1.fluid":"minecraft:water","input.1.fluid_amount":"125","output.item":"minecraft:white_concrete","output.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:bottling_machine'
            'input.item' = 'minecraft:white_concrete_powder'
            'input.count' = 1
            'fluid.fluid' = 'minecraft:water'
            'fluid.amount' = 125
            'results.0.id' = 'minecraft:white_concrete'
            'results.0.count' = 1
        }
        Absent = @('fluid.tag', 'input.tag', 'input.basePredicate')
    },
    # data/immersiveengineering/recipe/bottling/empty_shell.json is
    #   {"type":"immersiveengineering:bottling_machine",
    #    "fluid":{"amount":250,"tag":"c:phenolic_resin"},
    #    "inputs":[{"item":"immersiveengineering:mold_bullet_casing"},
    #              {"basePredicate":{"tag":"c:nuggets/copper"},"count":3}],
    #    "results":[{"count":2,"id":"immersiveengineering:empty_shell"},
    #               {"id":"immersiveengineering:mold_bullet_casing"}]}
    # - three input slots (two items and the fluid, in that order) and two outputs, so
    # input.2 is the fluid and the ordinals after it are the results. The rewritten
    # second item keeps its count and loses the tag it was written as.
    @{
        Name = 'immersiveengineering:bottling_machine (inputs array, 2 items)'
        RecipeId = 'immersiveengineering:bottling/empty_shell'
        Serializer = 'immersiveengineering:bottling_machine'
        RecipePath = 'data\immersiveengineering\recipe\bottling\empty_shell.json'
        Fields = '{"input.0.item":"minecraft:iron_ingot","input.0.count":"1","input.1.item":"minecraft:copper_ingot","input.1.count":"3","input.2.fluid":"minecraft:water","input.2.fluid_amount":"250","output.0.item":"minecraft:arrow","output.0.count":"2","output.1.item":"immersiveengineering:mold_bullet_casing","output.1.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:bottling_machine'
            'inputs.0.item' = 'minecraft:iron_ingot'
            'inputs.0.count' = 1
            'inputs.1.item' = 'minecraft:copper_ingot'
            'inputs.1.count' = 3
            'fluid.fluid' = 'minecraft:water'
            'fluid.amount' = 250
            'results.0.id' = 'minecraft:arrow'
            'results.0.count' = 2
            'results.1.id' = 'immersiveengineering:mold_bullet_casing'
            'results.1.count' = 1
        }
        Absent = @('inputs.1.basePredicate', 'fluid.tag')
    },
    # The two pages with no output slot at all share one sequence
    # (ImmersiveEngineeringSlotSequences.SingleItemInput): the fertilizer scales the
    # cloche's growth by "growthModifier" and the fuel burns in the blast furnace for
    # "time", both of which the declaration never names and must therefore survive.
    # data/immersiveengineering/recipe/fertilizer/bonemeal.json:
    #   {"type":"immersiveengineering:fertilizer","growthModifier":1.3,
    #    "input":{"item":"minecraft:bone_meal"}}
    @{
        Name = 'immersiveengineering:fertilizer (no output slot)'
        RecipeId = 'immersiveengineering:fertilizer/bonemeal'
        Serializer = 'immersiveengineering:fertilizer'
        RecipePath = 'data\immersiveengineering\recipe\fertilizer\bonemeal.json'
        Fields = '{"input.0.item":"minecraft:coal","input.0.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:fertilizer'
            'growthModifier' = 1.3
            'input.item' = 'minecraft:coal'
        }
        Absent = @('input.tag')
    },
    # data/immersiveengineering/recipe/blastfurnace/fuel_charcoal.json:
    #   {"type":"immersiveengineering:blast_furnace_fuel",
    #    "input":{"tag":"c:charcoal"},"time":300}
    @{
        Name = 'immersiveengineering:blast_furnace_fuel (no output slot)'
        RecipeId = 'immersiveengineering:blastfurnace/fuel_charcoal'
        Serializer = 'immersiveengineering:blast_furnace_fuel'
        RecipePath = 'data\immersiveengineering\recipe\blastfurnace\fuel_charcoal.json'
        Fields = '{"input.0.item":"minecraft:coal","input.0.count":"1"}'
        Expect = @{
            'type' = 'immersiveengineering:blast_furnace_fuel'
            'time' = 300
            'input.item' = 'minecraft:coal'
        }
        Absent = @('input.tag')
    },
    # A tag written into an item slot. The client carries a tag as its own id with a
    # leading '#' ('#ns:path'), which is how SlotPatchFields addresses a tag without a
    # second field, so the declared writer has to turn that into the mod's own tag shape:
    # '{"tag":"ns:path"}' and nothing else. An 'item' key holding '#ns:path', or a count
    # sitting beside the tag, is a node the mods' codecs reject - the shipped Create files
    # write the tag alone (this recipe's own ingredients[1] is {"tag":"minecraft:axes"}).
    @{
        Name = 'create:deploying (tag input)'
        RecipeId = 'create:deploying/copper_grate_from_deoxidising'
        Serializer = 'create:deploying'
        RecipePath = 'data\create\recipe\deploying\copper_grate_from_deoxidising.json'
        Fields = '{"input.0.item":"#c:ingots/copper","input.0.count":"1"}'
        Expect = @{
            'type' = 'create:deploying'
            'ingredients.0.tag' = 'c:ingots/copper'
            'ingredients.1.tag' = 'minecraft:axes'
            'keep_held_item' = $true
            'results.0.id' = 'minecraft:copper_grate'
        }
        Absent = @('ingredients.0.item', 'ingredients.0.count', 'ingredients.0.id')
    }
)

# --- probe cases: single patches whose outcome is a refusal or a rollback -----
#
# The client test reports the pages below as "NOT WIRED": the editor builds a model for
# them because the mod's recipe class extends a vanilla one, so nothing declares them.
# Each probe imports a patch against a real recipe of the page, on its own, and asserts
# what happens to the generated JSON. Written=$false means the server must refuse the
# patch and leave no file behind; Written=$true means the file must appear with the
# listed content. The last probe is the exception that proves the page-scoped
# declarations: it names a serializer whose two JEI pages are declared separately, so
# the serializer alone names no direction and has to be refused.
$probeCases = @(
    # The campfire pot's seasoning slots are previews with no field of their own: the recipe
    # names seasoning processors and a tag, not the seasonings. This file has three
    # ingredients and one processor, so the page draws a fourth input slot, and a patch
    # addressed to it has to be refused - the sequence resolves slots only for the entries
    # the JSON carries, so the ordinal is beyond everything it can name. Declared as the
    # field list "ingredients.%d" instead, this same patch would APPEND a fourth ingredient.
    @{
        Name = 'cobblemon:campfire_pot_recipe (a seasoning preview slot has no field)'
        Bundle = 'declared-probe-campfire-pot-seasoning'
        RecipeId = 'cobblemon:campfire_pot/black_aprijuice'
        Serializer = 'cobblemon:cooking_pot_shapeless'
        RecipePath = 'data\cobblemon\recipe\campfire_pot\black_aprijuice.json'
        Fields = '{"input.3.item":"minecraft:sugar","input.3.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # Create's mechanical crafting page draws one INPUT slot per pattern cell, blanks
    # included, but a blank cell has no "key" entry to edit. The sequence records the
    # blank's ordinal as a slot without a field, so a patch addressed to it is refused -
    # and, crucially, not treated as a prefix-count slot, which a field list would have
    # let through: crushing_wheel's pattern is [" AAA ", ...], so ordinal 0 is blank and
    # the letters only start at ordinal 1. The recipe is deliberately NOT wand_of_symmetry:
    # a probe removes its target file before importing, and that file is the declared
    # case's own artifact - pointing the probe at it would delete the case's output and
    # the after-reload assertion would find it missing.
    @{
        Name = 'create:mechanical_crafting (a blank pattern cell has no field)'
        Bundle = 'declared-probe-mechanical-crafting-blank'
        RecipeId = 'create:mechanical_crafting/crushing_wheel'
        Serializer = 'create:mechanical_crafting'
        RecipePath = 'data\create\recipe\mechanical_crafting\crushing_wheel.json'
        Fields = '{"input.0.item":"minecraft:iron_ingot","input.0.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # The sequenced assembly sequence resolves one slot per step that draws one, so an ordinal
    # past them refuses the whole patch rather than writing somewhere nearby. track draws three
    # inputs (the base ingredient plus two deploying steps), so input.3 names no field. It is
    # deliberately NOT one of the two files the declared cases use: a probe deletes its target
    # before importing, which would take a declared case's output with it.
    @{
        Name = 'create:sequenced_assembly (an ordinal past the steps the page draws)'
        Bundle = 'declared-probe-sequenced-assembly-past-steps'
        RecipeId = 'create:sequenced_assembly/track'
        Serializer = 'create:sequenced_assembly'
        RecipePath = 'data\create\recipe\sequenced_assembly\track.json'
        Fields = '{"input.3.item":"minecraft:iron_ingot","input.3.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # A clear on a FIXED array element. The clear gesture removes an element of a
    # variable-arity list, because neither "minecraft:air" nor a zero count parses in the
    # mods' codecs; on a fixed field that removal shrinks a list the mod may require.
    # Create's item_application page is the case that proved it: it draws two inputs and
    # ItemApplicationRecipe.getRequiredHeldItem() throws "Item Application Recipe has no
    # tool!" as soon as the decoded list holds fewer than two entries - Create's own JEI
    # category builds its layout through that method, so the exception takes JEI's whole
    # recipe area down. The patch below is refused by the pre-write gate and nothing is
    # written; the declared case below clears a REPEAT element instead and still removes
    # it, so the two halves of the rule are pinned together.
    @{
        Name = 'create:item_application (a fixed element cannot be cleared)'
        Bundle = 'declared-probe-item-application-clear'
        RecipeId = 'create:item_application/brass_casing_from_log'
        Serializer = 'create:item_application'
        RecipePath = 'data\create\recipe\item_application\brass_casing_from_log.json'
        Fields = '{"input.1.item":"minecraft:air","input.1.count":"0"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # A recipe whose original JSON carries a neoforge:conditions block. The vanilla
    # rebuild path rebuilds the record from the recipe object, which does not carry the
    # condition, so until this probe the condition was dropped and the edit silently
    # made a gated recipe unconditional (103 of the pack's vanilla-typed recipe files
    # carry one). The block is copied from the original JSON, so it survives verbatim
    # while "category" - the other record field this path used to drop - is written back
    # too.
    # data/create/recipe/smelting/ingot_lead_compat_mekanism.json is
    #   {"neoforge:conditions":[{"type":"neoforge:mod_loaded","modid":"mekanism"}],
    #    "type":"minecraft:smelting","category":"blocks","cookingtime":200,"experience":0.1,
    #    "ingredient":{"item":"create:crushed_raw_lead"},
    #    "result":{"id":"mekanism:ingot_lead"}}
    @{
        Name = 'create:fan_blasting (a gated smelting recipe keeps its neoforge:conditions)'
        Bundle = 'declared-probe-smelting-conditions'
        RecipeId = 'create:smelting/ingot_lead_compat_mekanism'
        Serializer = 'minecraft:smelting'
        RecipePath = 'data\create\recipe\smelting\ingot_lead_compat_mekanism.json'
        Fields = '{"output.item":"minecraft:iron_ingot","output.count":"2","recipe.cooking_time":"100"}'
        BaseSlots = ('create:smelting/ingot_lead_compat_mekanism|minecraft:smelting' +
            '|input.0=input:item:create:crushed_raw_lead:1' +
            '|output=output:item:mekanism:ingot_lead:1|experience=0.1|cooking_time=200')
        Written = $true
        Expect = @{
            'type' = 'minecraft:smelting'
            'category' = 'blocks'
            'ingredient.item' = 'create:crushed_raw_lead'
            'experience' = 0.1
            'cookingtime' = 100
            'result.id' = 'minecraft:iron_ingot'
            'result.count' = 2
            'neoforge:conditions.0.type' = 'neoforge:mod_loaded'
            'neoforge:conditions.0.modid' = 'mekanism'
        }
    },
    # The three pages below are the A1 rows the checklist marks `done` without any
    # declaration: what they display *is* the recipe the recipe manager holds (the dump's
    # serializer column names it), so the editor models them through the vanilla adapter
    # and the patch carries that recipe's own serializer - the same path the vanilla page
    # of that serializer uses. Each probe pins one serializer on the Create page that
    # motivated it: block_cutting draws the minecraft:stonecutting recipes, fan_smoking
    # the minecraft:smoking ones, and automatic_shaped the minecraft:crafting_shapeless
    # ones (its automatic_packing sibling, a minecraft:crafting_shaped page, is above).
    # None of them is declared, so the fingerprint is the real one and each BaseSlots
    # spells out the model the server rebuilds from the recipe itself.
    @{
        Name = 'create:block_cutting (…_stonecutting, minecraft:stonecutting)'
        Bundle = 'declared-probe-block-cutting'
        RecipeId = 'create:exposed_copper_shingle_stairs_from_exposed_copper_shingles_stonecutting'
        Serializer = 'minecraft:stonecutting'
        RecipePath = 'data\create\recipe\exposed_copper_shingle_stairs_from_exposed_copper_shingles_stonecutting.json'
        # data/create/recipe/exposed_copper_shingle_stairs_from_exposed_copper_shingles_stonecutting.json is
        #   {"type":"minecraft:stonecutting","ingredient":{"item":"create:exposed_copper_shingles"},
        #    "result":{"count":1,"id":"create:exposed_copper_shingle_stairs"}}
        # - a stonecutting recipe whose ingredient is one item, so the fingerprint needs no
        # tag resolution.
        Fields = '{"input.0.item":"minecraft:stone","input.0.count":"1","output.item":"minecraft:stone_stairs","output.count":"4"}'
        BaseSlots = ('create:exposed_copper_shingle_stairs_from_exposed_copper_shingles_stonecutting' +
            '|minecraft:stonecutting|input.0=input:item:create:exposed_copper_shingles:1' +
            '|output=output:item:create:exposed_copper_shingle_stairs:1')
        Written = $true
        Expect = @{
            'type' = 'minecraft:stonecutting'
            'ingredient.item' = 'minecraft:stone'
            'result.id' = 'minecraft:stone_stairs'
            'result.count' = 4
        }
        Absent = @('ingredient.tag')
    },
    @{
        Name = 'create:fan_smoking (create:smoking/bread, minecraft:smoking)'
        Bundle = 'declared-probe-fan-smoking'
        RecipeId = 'create:smoking/bread'
        Serializer = 'minecraft:smoking'
        RecipePath = 'data\create\recipe\smoking\bread.json'
        # data/create/recipe/smoking/bread.json is
        #   {"type":"minecraft:smoking","category":"food","cookingtime":100,"experience":0.0,
        #    "ingredient":{"item":"create:dough"},"result":{"count":1,"id":"minecraft:bread"}}
        Fields = '{"output.item":"minecraft:cooked_porkchop","output.count":"2","recipe.cooking_time":"150"}'
        BaseSlots = ('create:smoking/bread|minecraft:smoking|input.0=input:item:create:dough:1' +
            '|output=output:item:minecraft:bread:1|experience=0.0|cooking_time=100')
        Written = $true
        Expect = @{
            'type' = 'minecraft:smoking'
            'category' = 'food'
            'ingredient.item' = 'create:dough'
            'experience' = 0.0
            'cookingtime' = 150
            'result.id' = 'minecraft:cooked_porkchop'
            'result.count' = 2
        }
    },
    @{
        Name = 'create:automatic_shaped (…pink_table_cloth_clear, minecraft:crafting_shapeless)'
        Bundle = 'declared-probe-auto-shaped'
        RecipeId = 'create:crafting/logistics/pink_table_cloth_clear'
        Serializer = 'minecraft:crafting_shapeless'
        RecipePath = 'data\create\recipe\crafting\logistics\pink_table_cloth_clear.json'
        # data/create/recipe/crafting/logistics/pink_table_cloth_clear.json is
        #   {"type":"minecraft:crafting_shapeless","category":"misc",
        #    "ingredients":[{"item":"create:pink_table_cloth"}],
        #    "result":{"count":1,"id":"create:pink_table_cloth"}}
        # The rewritten ingredient is written in the vanilla shape, which carries no count.
        Fields = '{"input.4.item":"minecraft:white_wool","input.4.count":"1"}'
        # A shapeless recipe is modelled on the 3x3 grid JEI draws, so its one
        # ingredient sits in the middle cell (input.4) and the other eight slots are
        # empty - the client report's modelled_samples spells exactly that out for
        # create:crafting/logistics/pink_table_cloth_clear, which is what the grid
        # below repeats.
        BaseSlots = ('create:crafting/logistics/pink_table_cloth_clear|minecraft:crafting_shapeless' +
            '|input.0=input' +
            '|input.1=input' +
            '|input.2=input' +
            '|input.3=input' +
            '|input.4=input:item:create:pink_table_cloth:1' +
            '|input.5=input' +
            '|input.6=input' +
            '|input.7=input' +
            '|input.8=input' +
            '|output=output:item:create:pink_table_cloth:1')
        Written = $true
        Expect = @{
            'type' = 'minecraft:crafting_shapeless'
            'category' = 'misc'
            'ingredients.0.item' = 'minecraft:white_wool'
            'result.id' = 'create:pink_table_cloth'
            'result.count' = 1
        }
        Absent = @('ingredients.0.count')
    },
    # jerm:jerm_repair is JEI's own anvil page with mod supplied entries: the client
    # report models it entirely through the "jei-generated" route with serializer
    # "jei:anvil" and ids "jei:anvil_<hash>". Those pages are read-only by design - the
    # client never offers the edit (JeiRecipeEditorPlugin.visibleEditableTargets skips an
    # existing anvil page) and RecipeEditsApplier.canApplyAnvil refuses every non-creation
    # anvil patch - so this pins the server half of that contract: JEI's anvil entries are
    # not in the recipe manager at all, so the import has to be refused before anything is
    # written. The candidate path is the anvil data path anvilDataPath() would use if the
    # patch passed; it must not exist afterwards.
    @{
        Name = 'jerm:jerm_repair (jei:anvil entry)'
        Bundle = 'declared-probe-jerm-anvil'
        RecipeId = 'jei:anvil_2324336ceca8c38747078232'
        Serializer = 'jei:anvil'
        RecipePath = 'data\jeieditor\jei_editor\anvil\anvil_2324336ceca8c38747078232.json'
        Fields = '{"output.item":"minecraft:diamond","output.count":"1"}'
        Written = $false
        # "stale or unsupported patch" is the import command's pre-write gate: JEI's anvil
        # entries have no holder in the recipe manager, so nothing is ever written.
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # create:automatic_packing is Create's PackingCategory page, whose displayed recipes
    # are RecipeHolder<BasinRecipe> objects produced by BasinRecipe.convertShapeless for
    # JEI display; the client report shows that page's serializer as "create:basin". The
    # model, however, is built from the underlying recipe the recipe manager still holds -
    # resolution falls back to byKey(recipeId) - and the report's samples name
    # "minecraft:bone_block [minecraft:crafting_shaped via recipe-holder]". So the patch
    # the editor produces carries the *underlying* recipe's serializer, not "create:basin",
    # and the write goes through the plain vanilla crafting branch.
    #
    # minecraft:bone_block is a 3x3 shaped recipe whose nine keys are all bone_meal, which
    # the category dump corroborates (nine input candidates, all bone_meal, then bone_block
    # as the output). The base model is therefore nine input slots of bone_meal and one
    # bone_block output, and BaseSlots spells that out for the fingerprint. This is also the
    # guard that the serializer gate added to the vanilla adapters did not break a page
    # whose patch really does carry the underlying vanilla serializer.
    @{
        Name = 'create:automatic_packing (minecraft:bone_block, minecraft:crafting_shaped)'
        Bundle = 'declared-probe-auto-packing'
        RecipeId = 'minecraft:bone_block'
        Serializer = 'minecraft:crafting_shaped'
        RecipePath = 'data\minecraft\recipe\bone_block.json'
        Fields = '{"input.0.item":"minecraft:gold_ingot","input.0.count":"1"}'
        BaseSlots = ('minecraft:bone_block|minecraft:crafting_shaped|' +
            ((0..8 | ForEach-Object { "input.$_=input:item:minecraft:bone_meal:1" }) -join '|') +
            '|output=output:item:minecraft:bone_block:1')
        Written = $true
        Expect = @{
            'type' = 'minecraft:crafting_shaped'
            'category' = 'building'
            'pattern.0' = 'abc'
            'pattern.1' = 'def'
            'pattern.2' = 'ghi'
            'key.a.item' = 'minecraft:gold_ingot'
            'result.id' = 'minecraft:bone_block'
            'result.count' = 1
        }
        Absent = @()
    },
    # The other half of the mekanism:sawing declaration: output.1 is drawn for every recipe
    # but 40 of the 124 carry no secondary, and the client refuses that empty slot (the
    # client test's C assertion reports it). This probe asks the server the same question
    # about a recipe that has no "secondary_output". The patch is accepted by the pre-write
    # gate - the serializer is declared and "secondary_output" is a field the declaration
    # names - so the file IS written, with a stray "secondary_output" and no
    # "secondary_chance"; the targeted re-parse then rejects it ("Missing value": Mekanism's
    # codec requires the dependent field), the file is rolled back and the import fails.
    # Hence the case is not a pre-write refusal: the refusal has to come from the codec
    # after the write, and no file may be left behind.
    # data/mekanism/recipe/sawing/trapdoor/cherry.json is
    #   {"type":"mekanism:sawing","input":{"count":1,"item":"minecraft:cherry_trapdoor"},
    #    "main_output":{"count":3,"id":"minecraft:cherry_planks"}}
    @{
        Name = 'mekanism:sawing (no secondary, output.1)'
        Bundle = 'declared-probe-sawing-no-secondary'
        RecipeId = 'mekanism:sawing/trapdoor/cherry'
        Serializer = 'mekanism:sawing'
        RecipePath = 'data\mekanism\recipe\sawing\trapdoor\cherry.json'
        Fields = '{"output.1.item":"minecraft:string","output.1.count":"1"}'
        # The codec's own error ("Import failed: Missing value", Mekanism requiring the
        # dependent secondary_chance) was observed once, but RCON delivers that asynchronous
        # failure either as this import's response or as a separate packet, so the text is
        # evidence rather than the assertion. What is asserted is the invariant: an accepted
        # write of a stray "secondary_output" must not survive.
        Outcome = 'rolled-back'
    },
    # create:automatic_shapeless is Create's MixingCategory page. Its recipes are also
    # RecipeHolder objects converted for display, but here the underlying recipes are the
    # pack's shapeless crafting recipes - the client report's samples are
    # "refinedstorage:coloring/magenta_destructor [refinedstorage:recoloring]" and
    # "create:crafting/logistics/black_postbox_from_other_postbox [minecraft:crafting_shapeless]".
    # Refined Storage's RecoloringRecipe extends ShapelessRecipe, which is why the editor
    # models it as a two-ingredient crafting grid, so the patch the editor produces carries
    # serializer "refinedstorage:recoloring" and the write takes the (non-declared) shapeless
    # crafting branch: it rebuilds {"type","category","ingredients","result"}. Refined
    # Storage's own codec is
    #   RecordCodecBuilder.group(Ingredient.CODEC.fieldOf("ingredient"),
    #                            Ingredient.CODEC.fieldOf("dye"),
    #                            ItemStack.ITEM_NON_AIR_CODEC.fieldOf("result"))
    # - the real JSON is
    #   {"type":"refinedstorage:recoloring","dye":{"tag":"c:dyes/magenta"},
    #    "ingredient":{"tag":"refinedstorage:destructors"},"result":"refinedstorage:magenta_destructor"}
    # - so the rebuilt file has neither "ingredient" nor "dye", and Refined Storage's
    # codec rejects it. That was a real half-state: CraftingRecipeEditorAdapter matched on
    # "instanceof ShapelessRecipe", so the editor offered the slot while the written JSON
    # could not be read back. The adapter now claims only minecraft:crafting_shaped /
    # minecraft:crafting_shapeless, so the page no longer models and the patch is refused
    # by the pre-write gate instead - "stale or unsupported patch" comes from the import
    # command's hasModel/canApply check, before any file is written. The case is kept as
    # the regression guard for that fix: if class-based matching ever comes back, the
    # response changes (a codec parse error, or even a written file) and this fails.
    @{
        Name = 'create:automatic_shapeless (refinedstorage:recoloring)'
        Bundle = 'declared-probe-auto-shapeless'
        RecipeId = 'refinedstorage:coloring/magenta_destructor'
        Serializer = 'refinedstorage:recoloring'
        RecipePath = 'data\refinedstorage\recipe\coloring\magenta_destructor.json'
        Fields = '{"input.1.item":"minecraft:lime_dye","input.1.count":"1"}'
        BaseSlots = ('refinedstorage:coloring/magenta_destructor|refinedstorage:recoloring|' +
            'input.0=input:item:refinedstorage:white_destructor:1|input.1=input:item:minecraft:magenta_dye:1|' +
            ((2..8 | ForEach-Object { "input.$_=input" }) -join '|') +
            '|output=output:item:refinedstorage:magenta_destructor:1')
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # The gear_crafting page draws its ingredient list as a grid (Silent Gear's own
    # category lays entry i at cell i), so the page's slots ARE the list's ordinals:
    # input.0..input.(n-1) are the n ingredients, and the first cell beyond the list is the
    # variable-arity "spare slot" the declared mechanism documents. This probe pins what a
    # drop there does on the compound_part type - it APPENDS an ingredient instead of
    # rewriting one - which is the same contract create:blueprint's inputs.%d has.
    # (The probe used to assert the opposite: before the page was declared, its patch was
    # refused by the pre-write gate, because the recipe class extends ShapelessRecipe and
    # only the crafting adapter modelled it.)
    @{
        Name = 'silentgear:gear_crafting (a drop into the first empty grid cell appends)'
        Bundle = 'declared-probe-silentgear'
        RecipeId = 'silentgear:gear/sickle_head'
        Serializer = 'silentgear:compound_part'
        RecipePath = 'data\silentgear\recipe\gear\sickle_head.json'
        Fields = '{"input.4.item":"minecraft:oak_planks","input.4.count":"1"}'
        # The model the client builds for this recipe (report.json's modelled_samples):
        # its four ingredients sit in cells 0..3 and cells 4..8 are empty.
        BaseSlots = ('silentgear:gear/sickle_head|silentgear:compound_part|' +
            'input.0=input:item:silentgear:sickle_blueprint:1|' +
            'input.1=input:item:minecraft:bamboo_planks:1|' +
            'input.2=input:item:minecraft:bamboo_planks:1|' +
            'input.3=input:item:minecraft:bamboo_planks:1|' +
            ((4..8 | ForEach-Object { "input.$_=input" }) -join '|') +
            '|output=output:item:silentgear:sickle_blade:1')
        Written = $true
        Expect = @{
            'type' = 'silentgear:compound_part'
            'category' = 'equipment'
            'ingredients.0.type' = 'silentgear:blueprint'
            'ingredients.1.type' = 'silentgear:material'
            'ingredients.2.type' = 'silentgear:material'
            'ingredients.3.type' = 'silentgear:material'
            'ingredients.4.item' = 'minecraft:oak_planks'
            'ingredients.4.count' = 1
            'result.id' = 'silentgear:sickle_blade'
            'result.count' = 1
        }
    },
    # The serializer whose JEI pages are declared per page rather than per serializer:
    # mekanism:rotary is shown by mekanism:condensentrating (chemical_input + fluid_output)
    # and by mekanism:decondensentrating (fluid_input + chemical_output), and both pages read
    # the same recipe JSON. A patch that names only the serializer therefore names no
    # direction, so it must be refused instead of being written into one arbitrary pair of
    # fields - that is what keeps the two page-scoped declarations from collapsing into one.
    # The refusal comes from the pre-write gate (the serializer is not declared at all), so
    # no file may be left behind; the fingerprint does not matter there. A third rotary
    # recipe keeps this probe off the files the two declared cases write.
    @{
        Name = 'mekanism:rotary (the serializer alone is not a declaration)'
        Bundle = 'declared-probe-rotary-serializer'
        RecipeId = 'mekanism:rotary/oxygen'
        Serializer = 'mekanism:rotary'
        RecipePath = 'data\mekanism\recipe\rotary\oxygen.json'
        Fields = '{"input.0.chemical":"mekanism:brine","input.0.chemical_amount":"5"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # create:automatic_brewing shares create:mixing's serializer and its category class,
    # but its 286 entries come from PotionMixingRecipes#createRecipes(Level), a static list
    # built from Level.potionBrewing() that the mixer reads in world: the id below is the
    # one the client dump shows for the page, and it is in no registry, so the import is
    # refused by the pre-write gate with nothing written. That is why the declaration names
    # the page read-only, and this probe pins the server half of it.
    @{
        Name = 'create:automatic_brewing (synthesised potion mixing recipe)'
        Bundle = 'declared-probe-auto-brewing'
        RecipeId = 'create:potion_mixing_vanilla_0'
        Serializer = 'create:mixing'
        RecipePath = 'data\create\recipe\potion_mixing_vanilla_0.json'
        Fields = '{"input.0.item":"minecraft:gunpowder","input.0.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # --- the families whose recipes the manager does not hold -----------------
    #
    # One probe per family, pinning the server-side consequence of a provenance
    # that was measured with a temporary server-side probe on this same pack (the
    # counts and the resource-manager answers are recorded in
    # docs/JEI_PAGE_COMPAT_TODO.md, "运行时合成页面的真实来源"): of 8576 recipes in
    # the manager, not one has an unreachable recipe JSON, and every synthesised
    # entry below is in no registry at all, so the honest end state for those
    # pages is a clean refusal with no file written.
    #
    # create:draining, 2 of its 80 entries in the manager: ItemDrainCategory
    # synthesises the rest from each item's fluid handler, under an id no registry
    # has (create:empty_<item>_of_<fluid>). This is that id, straight from the
    # client dump, so the probe is a real page entry rather than an invented one.
    @{
        Name = 'create:draining (synthesised emptying recipe)'
        Bundle = 'declared-probe-create-draining'
        RecipeId = 'create:empty_minecraft_water_bucket_of_minecraft_water'
        Serializer = 'create:emptying'
        RecipePath = 'data\create\recipe\empty_minecraft_water_bucket_of_minecraft_water.json'
        Fields = '{"input.0.item":"minecraft:gunpowder","input.0.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # create:spout_filling is declared through CreateSpoutSlotSequence, which resolves
    # input.1 to the recipe's FLUID entry by the entry's own kind. This probe writes an
    # ITEM into that slot: a derived declaration accepts both kinds for the page's inputs
    # (which band an ordinal falls into is only decidable from the recipe JSON, and the
    # server decides it there), so the pre-write gate takes the patch and the derivation
    # refuses it - the kind of the field it resolved is not the kind the patch carries -
    # with nothing written. The recipe here writes its fluid as a
    # {"type":"neoforge:components",…} entry, so it also pins that the derivation counts
    # any registered fluid ingredient type as the fluid half. The page's own sample is
    # covered by the declared case above.
    @{
        Name = 'create:spout_filling (an item cannot be written into the fluid slot)'
        Bundle = 'declared-probe-create-spout'
        RecipeId = 'create:filling/glowstone'
        Serializer = 'create:filling'
        RecipePath = 'data\create\recipe\filling\glowstone.json'
        Fields = '{"input.1.item":"minecraft:oak_planks","input.1.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # immersiveengineering:arc_recycling, 0 of its 188 entries in the manager: the
    # arc_furnace serializer's 54 recipes are the ones IE ships as JSON, and
    # arc_recycling_list.json is a single generator recipe whose entries
    # (immersiveengineering:arc_recycling_list000...) are materialised into IE's own
    # CachedRecipeList. This id is the page's sample, from the client dump.
    @{
        Name = 'immersiveengineering:arc_recycling (generated list entry)'
        Bundle = 'declared-probe-arc-recycling'
        RecipeId = 'immersiveengineering:arc_recycling_list000'
        Serializer = 'immersiveengineering:arc_furnace'
        RecipePath = 'data\immersiveengineering\recipe\arc_recycling_list000.json'
        Fields = '{"input.0.item":"minecraft:copper_ingot","input.0.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # jearchaeology:brush and :sniff, 0 entries in the manager of a server without
    # a player: JEArchaeology builds them from loot tables in Helper and pushes
    # them into the manager from OnDatapackSyncEvent, which fires per player, and
    # the jar ships no data/** recipe files at all. The page's sample ids are used
    # so the probe names what the client really displays.
    @{
        Name = 'jearchaeology:brush (loot-table recipe of a player-less server)'
        Bundle = 'declared-probe-jarch-brush'
        RecipeId = 'jearchaeology:archaeology/ocean_ruin_warm'
        Serializer = 'jearchaeology:brush'
        RecipePath = 'data\jearchaeology\recipe\archaeology\ocean_ruin_warm.json'
        Fields = '{"input.0.item":"minecraft:suspicious_sand","input.0.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    @{
        Name = 'jearchaeology:sniff (loot-table recipe of a player-less server)'
        Bundle = 'declared-probe-jarch-sniff'
        RecipeId = 'jearchaeology:sniffing'
        Serializer = 'jearchaeology:sniff'
        RecipePath = 'data\jearchaeology\recipe\sniffing.json'
        Fields = '{"output.0.item":"minecraft:torchflower_seeds","output.0.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # mekanism:smelting, 0 entries in the manager: MekanismRecipeType builds the
    # page's 284 entries from the vanilla minecraft:smelting recipes and renames
    # each one through RecipeViewerUtils.synthetic(id, "mekanism_generated"), so
    # the id the page shows is in no registry. A patch naming it is refused.
    @{
        Name = 'mekanism:smelting (the page id is a rewritten vanilla id)'
        Bundle = 'declared-probe-mekanism-synthetic'
        RecipeId = 'cobblemon:/mekanism_generated/passho_berry_smelt_to_dye'
        Serializer = 'mekanism:smelting'
        RecipePath = 'data\cobblemon\recipe\mekanism_generated\passho_berry_smelt_to_dye.json'
        Fields = '{"input.0.item":"minecraft:gunpowder","input.0.count":"1"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    # ... while the recipe behind that alias is an ordinary datapack recipe, and
    # that is what the editor resolves the page entry to (RecipeViewerAliases):
    # this patch carries the real id and the real serializer, so it is written and
    # re-parsed like any other cooking edit. The fingerprint is the real one (the
    # page is not declared, so the server compares it): the input and output of
    # data/cobblemon/recipe/passho_berry_smelt_to_dye.json, plus the two cooking
    # properties. The group is part of the record and must survive, which is why
    # this probe asserts it.
    @{
        Name = 'mekanism:smelting (the alias resolves to the vanilla smelting recipe)'
        Bundle = 'declared-probe-mekanism-alias'
        RecipeId = 'cobblemon:passho_berry_smelt_to_dye'
        Serializer = 'minecraft:smelting'
        RecipePath = 'data\cobblemon\recipe\passho_berry_smelt_to_dye.json'
        Fields = '{"output.item":"minecraft:lapis_lazuli","output.count":"3",' +
            '"recipe.cooking_time":"300"}'
        BaseSlots = ('cobblemon:passho_berry_smelt_to_dye|minecraft:smelting|' +
            'input.0=input:item:cobblemon:passho_berry:1|' +
            'output=output:item:minecraft:blue_dye:1|experience=0.1|cooking_time=200')
        Written = $true
        Expect = @{
            'type' = 'minecraft:smelting'
            'group' = 'berry_dyes'
            'ingredient.item' = 'cobblemon:passho_berry'
            'experience' = 0.1
            'cookingtime' = 300
            'result.id' = 'minecraft:lapis_lazuli'
            'result.count' = 3
        }
    },
    # --- JEI's tag pages (minecraft:tag_recipes/item|block|fluid) --------------
    #
    # A tag page is not a recipe: it lists a tag's member list, so editing it is a tag
    # patch - serializer "jeieditor:tag", and the recipe id encodes the registry and the
    # tag id as jeieditor:tag/<registry>/<tag namespace>/<tag path> (one tag id can exist
    # in two registries, and both the bundle and the world SavedData key patches by recipe
    # id, so the registry has to be part of the key). The server writes the tag's WHOLE
    # member list with "replace": true into the generated pack
    # (data/<tag namespace>/tags/<registry>/<path>.json) and then reloads, because a tag
    # has no hot-patch equivalent to a recipe: JEI's tag pages and every recipe that uses
    # the tag read the tag map the last resource reload bound.
    #
    # The first four probes walk one tag's whole life - create, add, remove, delete - in
    # order, each on the state the one before it left behind, so their expectations are
    # the member lists themselves. The fifth adds to a vanilla tag and pins the point of
    # the snapshot: the members other packs already contribute are carried into the file
    # rather than being replaced by the one member the user added.
    #
    # The first three are chained steps over one file, so only the last of them can carry
    # the after-reload re-assertion: LaterStep says a later probe deliberately rewrites
    # this probe's file. Each step's own import is still asserted when it happens.
    @{
        Name = 'jeieditor:tag (create a tag with its first member)'
        Bundle = 'declared-probe-tag-create'
        RecipeId = 'jeieditor:tag/item/jeieditor/smoke_item'
        Serializer = 'jeieditor:tag'
        RecipePath = 'data\jeieditor\tags\item\smoke_item.json'
        Fields = '{"tag.op":"tag.create","tag.registry":"item","tag.element":"minecraft:iron_ingot"}'
        Written = $true
        LaterStep = $true
        Expect = @{
            'values.0' = 'minecraft:iron_ingot'
            'replace' = $true
        }
        Absent = @('values.1')
    },
    @{
        Name = 'jeieditor:tag (add a second member)'
        Bundle = 'declared-probe-tag-add'
        RecipeId = 'jeieditor:tag/item/jeieditor/smoke_item'
        Serializer = 'jeieditor:tag'
        RecipePath = 'data\jeieditor\tags\item\smoke_item.json'
        Fields = '{"tag.op":"member.add","tag.registry":"item","tag.element":"minecraft:gold_ingot"}'
        Written = $true
        LaterStep = $true
        Expect = @{
            'values.0' = 'minecraft:iron_ingot'
            'values.1' = 'minecraft:gold_ingot'
            'replace' = $true
        }
        Absent = @('values.2')
    },
    @{
        Name = 'jeieditor:tag (remove a member)'
        Bundle = 'declared-probe-tag-remove'
        RecipeId = 'jeieditor:tag/item/jeieditor/smoke_item'
        Serializer = 'jeieditor:tag'
        RecipePath = 'data\jeieditor\tags\item\smoke_item.json'
        Fields = '{"tag.op":"member.remove","tag.registry":"item","tag.element":"minecraft:iron_ingot"}'
        Written = $true
        LaterStep = $true
        Expect = @{
            'values.0' = 'minecraft:gold_ingot'
            'replace' = $true
        }
        Absent = @('values.1')
    },
    @{
        Name = 'jeieditor:tag (delete the tag, which empties it)'
        Bundle = 'declared-probe-tag-delete'
        RecipeId = 'jeieditor:tag/item/jeieditor/smoke_item'
        Serializer = 'jeieditor:tag'
        RecipePath = 'data\jeieditor\tags\item\smoke_item.json'
        Fields = '{"tag.op":"tag.delete","tag.registry":"item"}'
        Written = $true
        Expect = @{
            'replace' = $true
        }
        Absent = @('values.0')
    },
    @{
        Name = 'jeieditor:tag (add to a vanilla tag keeps the members other packs define)'
        Bundle = 'declared-probe-tag-vanilla'
        RecipeId = 'jeieditor:tag/fluid/minecraft/water'
        Serializer = 'jeieditor:tag'
        RecipePath = 'data\minecraft\tags\fluid\water.json'
        Fields = '{"tag.op":"member.add","tag.registry":"fluid","tag.element":"minecraft:lava"}'
        Written = $true
        Expect = @{
            'values.0' = 'minecraft:water'
            'values.1' = 'minecraft:flowing_water'
            'values.2' = 'minecraft:lava'
            'replace' = $true
        }
        Absent = @('values.3')
    },
    # The semantic preconditions: a member the registry does not know would make the loader
    # refuse the whole file on the next reload and take the tag's other members down with it,
    # and a delete of a tag that has no members describes nothing. Both are refused by the
    # pre-write gate, so nothing is written. They name their own tags on purpose: a probe
    # deletes its target file before importing, so aiming one at a tag an earlier probe wrote
    # would take that probe's artifact away and the after-reload re-assertion would find it
    # missing.
    @{
        Name = 'jeieditor:tag (an element no registry knows is refused)'
        Bundle = 'declared-probe-tag-unknown-element'
        RecipeId = 'jeieditor:tag/item/jeieditor/smoke_unknown'
        Serializer = 'jeieditor:tag'
        RecipePath = 'data\jeieditor\tags\item\smoke_unknown.json'
        Fields = '{"tag.op":"member.add","tag.registry":"item","tag.element":"minecraft:not_a_real_item"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    },
    @{
        Name = 'jeieditor:tag (deleting a tag with no members is refused)'
        Bundle = 'declared-probe-tag-delete-empty'
        RecipeId = 'jeieditor:tag/item/jeieditor/smoke_empty'
        Serializer = 'jeieditor:tag'
        RecipePath = 'data\jeieditor\tags\item\smoke_empty.json'
        Fields = '{"tag.op":"tag.delete","tag.registry":"item"}'
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
    }
)

$patches = New-Object System.Collections.Generic.List[string]
foreach ($case in $cases) {
    [void] $patches.Add('{"recipe_id":"' + $case.RecipeId + '","serializer":"' + $case.Serializer +
        '","base_fingerprint":"' + $slackFingerprint + '","fields":' + $case.Fields + '}')
}
$bundle = '{"format_version":1,"patches":[' + ($patches -join ',') + ']}'
$bundlePath = Join-Path $runDirectory 'config\jeieditor\exports\declared-fixture.json'
New-Item -ItemType Directory -Path (Split-Path -Parent $bundlePath) -Force | Out-Null
Set-Content -LiteralPath $bundlePath -Value $bundle -Encoding ascii

# --- the RCON transport ------------------------------------------------------
#
# The client lives in scripts/jei-rcon.ps1: a transient socket failure (or a
# receive timeout) reconnects and re-sends the command with exponential
# backoff, bounded by -MaxAttempts, so one closed socket cannot abort a
# ten-minute run. Every command this script sends is safe to re-issue.
. (Join-Path $PSScriptRoot 'jei-rcon.ps1')

function Get-JsonValue {
    param($Object, [string] $Path)
    $current = $Object
    foreach ($segment in $Path.Split('.')) {
        if ($null -eq $current) { return $null }
        # A numeric segment indexes an array, so a declared "inputs.2" or "results.1"
        # field can be asserted like an object key.
        if ($current -is [System.Array]) {
            if ($segment -notmatch '^\d+$') { return $null }
            $index = [int] $segment
            if ($index -ge $current.Count) { return $null }
            $current = $current[$index]
            continue
        }
        $property = $current.PSObject.Properties[$segment]
        if ($null -eq $property) { return $null }
        $current = $property.Value
    }
    return $current
}

function Assert-GeneratedRecipe {
    param($Case, [string] $GeneratedPath, [string] $Stage)
    if (-not (Test-Path -LiteralPath $GeneratedPath -PathType Leaf)) {
        throw "${Stage}: generated recipe was not written: $GeneratedPath"
    }
    $raw = Get-Content -LiteralPath $GeneratedPath -Raw
    $json = $raw | ConvertFrom-Json
    foreach ($key in $Case.Expect.Keys) {
        $actual = Get-JsonValue $json $key
        if ($actual -ne $Case.Expect[$key]) {
            throw ("${Stage}: $($Case.Name) expected $key = $($Case.Expect[$key]) but found '$actual'; file: $raw")
        }
    }
    # A case that asserts nothing is absent simply omits the key.
    if ($Case.ContainsKey('Absent')) {
        foreach ($key in $Case.Absent) {
            if ($null -ne (Get-JsonValue $json $key)) {
                throw "${Stage}: $($Case.Name) kept a stale $key; file: $raw"
            }
        }
    }
    Write-Host ("  ok  {0}  {1}" -f $Case.Name, ($raw -replace '\s+', ' '))
}

$serverProcess = $null
$rcon = $null
$requestId = 3000
try {
    $arguments = @('runServer', '--console', 'plain', '--no-daemon')
    if ($Offline) { $arguments += '--offline' }
    $argumentText = $arguments -join ' '
    $serverProcess = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/d', '/c', "$([char]34)$wrapper$([char]34) $argumentText") `
        -WorkingDirectory $targetDirectory -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath -PassThru

    Wait-RconPort -Port $RconPort -TimeoutSeconds $StartupTimeoutSeconds -Process $serverProcess `
        -Hint "$stdoutPath and $stderrPath"
    $rcon = New-RconConnection -Port $RconPort -Password $RconPassword
    Connect-Rcon $rcon

    $help = Invoke-Rcon $rcon ([ref] $requestId) 'help jeieditor'
    if ($help -notmatch 'jeieditor') { throw 'jeieditor command was not registered' }

    Write-Host 'Importing declared mod recipe patches...'
    $importResponse = Invoke-Rcon $rcon ([ref] $requestId) 'jeieditor import declared-fixture' `
        -RetryResponseRegex 'another recipe reload is in progress'
    if ($importResponse -match '(?i)failed') {
        throw "Import reported a failure: $importResponse"
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $written = @{}
    foreach ($case in $cases) {
        $written[$case.RecipeId] = Join-Path $worldDirectory ('datapacks\jeieditor-generated\' + $case.RecipePath)
    }
    # The wait is a poll, not a one-shot read: a slow disk or a loaded machine
    # delays the files without failing the run.
    [void] (Wait-ForCondition -TimeoutSeconds $StartupTimeoutSeconds -Condition {
            @($cases | Where-Object { -not (Test-Path -LiteralPath $written[$_.RecipeId] -PathType Leaf) }).Count -eq 0
        })
    $pending = @($cases | Where-Object { -not (Test-Path -LiteralPath $written[$_.RecipeId] -PathType Leaf) })
    if ($pending.Count -ne 0) {
        throw "Timed out waiting for generated recipes: $($pending.Name -join ', ')"
    }

    foreach ($case in $cases) {
        Assert-GeneratedRecipe -Case $case -GeneratedPath $written[$case.RecipeId] -Stage 'after import'
    }

    # --- probe imports: pages that model without a declaration ---------------
    #
    # One import per probe, so a refusal cannot abort the probes after it and the
    # single-import response is the evidence for that page. The fingerprint and the
    # response are printed for every probe; a refused probe additionally asserts that
    # the server wrote no file.
    #
    # A probe is retried while the server answers "another recipe reload is in progress":
    # the previous probe's apply is asynchronous, and that answer says nothing about this
    # probe. Without the retry a probe could "pass" as refused for a reason that has
    # nothing to do with its own page.
    Write-Host 'Probing the pages that model without a declaration...'
    $probeWritten = @{}
    foreach ($probe in $probeCases) {
        $fingerprint = if ($probe.ContainsKey('BaseSlots')) {
            Get-ModelFingerprint -Canonical $probe.BaseSlots
        } else {
            $slackFingerprint
        }
        $patch = '{"recipe_id":"' + $probe.RecipeId + '","serializer":"' + $probe.Serializer +
            '","base_fingerprint":"' + $fingerprint + '","fields":' + $probe.Fields + '}'
        $probeBundlePath = Join-Path $runDirectory ('config\jeieditor\exports\' + $probe.Bundle + '.json')
        Set-Content -LiteralPath $probeBundlePath -Value ('{"format_version":1,"patches":[' + $patch + ']}') -Encoding ascii
        $probePath = Join-Path $worldDirectory ('datapacks\jeieditor-generated\' + $probe.RecipePath)
        Remove-Item -LiteralPath $probePath -Force -ErrorAction SilentlyContinue
        $response = ''
        # The answer "another recipe reload is in progress" says nothing about this
        # probe - the previous probe's apply is asynchronous - so it is retried by
        # the transport itself before it can make a probe look "refused".
        $response = Invoke-Rcon $rcon ([ref] $requestId) ('jeieditor import ' + $probe.Bundle) `
            -RetryResponseRegex 'another recipe reload is in progress'
        if ($response -match 'another recipe reload is in progress') {
            throw ("{0}: the server never finished the previous import; the probe could not be measured" -f $probe.Name)
        }
        Write-Host ('  {0}' -f $probe.Name)
        Write-Host ('      base_fingerprint {0}' -f $fingerprint)
        Write-Host ('      response         {0}' -f (($response -replace '\s+', ' ').Trim()))
        # How this probe's outcome is asserted. "written" waits for the file and checks its
        # content; "rolled-back" asserts that a write the pre-write gate accepted did not
        # survive the codec re-parse; anything else is a synchronous pre-write refusal whose
        # reason is pinned by ExpectResponse.
        $outcome = if ($probe.ContainsKey('Outcome')) { $probe.Outcome }
            elseif ($probe.Written) { 'written' } else { 'refused-pre-write' }
        if ($outcome -eq 'written') {
            if ($response -match '(?i)import failed') {
                throw ("{0}: expected the server to write {1}, but the import failed: {2}" -f
                    $probe.Name, $probe.RecipePath, $response)
            }
            $probeDeadline = [DateTime]::UtcNow.AddSeconds(30)
            while (-not (Test-Path -LiteralPath $probePath -PathType Leaf) -and [DateTime]::UtcNow -lt $probeDeadline) {
                Start-Sleep -Milliseconds 500
            }
            Assert-GeneratedRecipe -Case $probe -GeneratedPath $probePath -Stage 'probe'
            $probeWritten[$probe.Name] = $probePath
        } elseif ($outcome -eq 'rolled-back') {
            # The patch passes the pre-write gate, the file is written, and the targeted
            # re-parse rejects it. RCON delivers that asynchronous failure either as this
            # import's response or as a separate packet, so the response text is only
            # evidence, not the assertion. What is asserted is the invariant: the stray
            # write must not survive. A pre-write refusal would mean the codec was never
            # reached, so that text is rejected here.
            if ($response -match 'stale or unsupported patch') {
                throw ("{0}: the patch was refused by the pre-write gate, so the codec was never reached; this case exists to prove the write is attempted and then rolled back" -f
                    $probe.Name)
            }
            Start-Sleep -Seconds 5
            $settle = [DateTime]::UtcNow.AddSeconds(20)
            while ((Test-Path -LiteralPath $probePath -PathType Leaf) -and [DateTime]::UtcNow -lt $settle) {
                Start-Sleep -Milliseconds 500
            }
            if (Test-Path -LiteralPath $probePath -PathType Leaf) {
                throw ("{0}: the server persisted the write: {1}" -f
                    $probe.Name, (Get-Content -LiteralPath $probePath -Raw))
            }
            Write-Host '      the write was rolled back; no generated file remains'
        } else {
            Start-Sleep -Seconds 2
            if ($response -notmatch '(?i)import failed') {
                $state = if (Test-Path -LiteralPath $probePath -PathType Leaf) {
                    'a generated file was left behind: ' + (Get-Content -LiteralPath $probePath -Raw)
                } else {
                    'no file was written'
                }
                throw ("{0}: expected the server to refuse the patch, but the import succeeded ({1})" -f
                    $probe.Name, $state)
            }
            if (Test-Path -LiteralPath $probePath -PathType Leaf) {
                throw ("{0}: the import failed but a generated file was left behind: {1}" -f
                    $probe.Name, (Get-Content -LiteralPath $probePath -Raw))
            }
            # A refusal has to be refused for the right reason: "stale or unsupported patch"
            # is the pre-write gate, while a codec rejection surfaces from the targeted
            # re-parse after the file was written. Pinning the text keeps a probe from
            # passing merely because a fingerprint went stale.
            if ($probe.ContainsKey('ExpectResponse') -and $response -notmatch $probe.ExpectResponse) {
                throw ("{0}: the import was refused, but for the wrong reason; expected the response to match '{1}' but it was: {2}" -f
                    $probe.Name, $probe.ExpectResponse, $response)
            }
            Write-Host '      refused, and no generated file exists'
        }
    }

    Write-Host 'Reloading and re-checking...'
    [void] (Invoke-Rcon $rcon ([ref] $requestId) 'reload')
    Start-Sleep -Seconds 2
    foreach ($case in $cases) {
        Assert-GeneratedRecipe -Case $case -GeneratedPath $written[$case.RecipeId] -Stage 'after reload'
    }
    foreach ($probe in $probeCases) {
        # A probe whose file a later probe deliberately rewrites - the create / add /
        # remove / delete steps over one tag, where each step's expectation is the state
        # the step before it left behind - is only asserted right after its own import.
        # Re-asserting it here would compare the sequence's final file against the
        # intermediate list that step wrote. The last step of such a sequence does not
        # set LaterStep, so the file still gets its after-reload proof.
        if ($probe.ContainsKey('LaterStep') -and $probe.LaterStep) {
            Write-Host ("  (skipped after-reload re-assertion for a chained step: {0})" -f $probe.Name)
            continue
        }
        if ($probeWritten.ContainsKey($probe.Name)) {
            Assert-GeneratedRecipe -Case $probe -GeneratedPath $probeWritten[$probe.Name] -Stage 'probe after reload'
        }
    }

    if ($RestartCycle) {
        # --- restart cycle ----------------------------------------------------
        #
        # The generated pack is a datapack of the world, and a full resource reload
        # writes the selected packs into the world data, so a restart has to load the
        # edits again from the files. This cycle proves that on the live recipes
        # rather than on the files: the patch below carries the fingerprint of the
        # ALREADY EDITED recipe (output lapis_lazuli x3, cooking time 300), so it is
        # only accepted while the server really holds those values. A server that had
        # fallen back to the mod's original recipe would compute a different
        # fingerprint and refuse it.
        Write-Host 'Stopping the server for the restart cycle...'
        [void] (Invoke-Rcon $rcon ([ref] $requestId) 'stop')
        Close-Rcon $rcon
        $rcon = $null
        [void] $serverProcess.WaitForExit(60000)
        if (-not $serverProcess.HasExited) {
            throw "Server did not stop for the restart cycle; logs: $logDirectory"
        }
        $serverProcess.Dispose()
        $serverProcess = $null

        $restartStdoutPath = Join-Path $logDirectory 'server.restart.stdout.log'
        $restartStderrPath = Join-Path $logDirectory 'server.restart.stderr.log'
        Remove-Item -LiteralPath $restartStdoutPath, $restartStderrPath -Force -ErrorAction SilentlyContinue
        $serverProcess = Start-Process -FilePath 'cmd.exe' `
            -ArgumentList @('/d', '/c', "$([char]34)$wrapper$([char]34) $argumentText") `
            -WorkingDirectory $targetDirectory -RedirectStandardOutput $restartStdoutPath `
            -RedirectStandardError $restartStderrPath -PassThru
        Write-Host '  waiting for the restarted server...'
        Wait-RconPort -Port $RconPort -TimeoutSeconds $StartupTimeoutSeconds -Process $serverProcess `
            -Hint "$restartStdoutPath and $restartStderrPath"
        $rcon = New-RconConnection -Port $RconPort -Password $RconPassword
        Connect-Rcon $rcon
        $requestId = 4000
        $help = Invoke-Rcon $rcon ([ref] $requestId) 'help jeieditor'
        if ($help -notmatch 'jeieditor') { throw 'jeieditor command was not registered after the restart' }

        $restartId = 'cobblemon:passho_berry_smelt_to_dye'
        $restartPath = Join-Path $worldDirectory 'datapacks\jeieditor-generated\data\cobblemon\recipe\passho_berry_smelt_to_dye.json'
        $restartSlots = ('cobblemon:passho_berry_smelt_to_dye|minecraft:smelting|' +
            'input.0=input:item:cobblemon:passho_berry:1|' +
            'output=output:item:minecraft:lapis_lazuli:3|experience=0.1|cooking_time=300')
        $restartFingerprint = Get-ModelFingerprint -Canonical $restartSlots
        $restartPatch = '{"recipe_id":"' + $restartId +
            '","serializer":"minecraft:smelting","base_fingerprint":"' + $restartFingerprint +
            '","fields":{"output.count":"5"}}'
        Set-Content -LiteralPath (Join-Path $runDirectory 'config\jeieditor\exports\declared-restart-fixture.json') `
            -Value ('{"format_version":1,"patches":[' + $restartPatch + ']}') -Encoding ascii
        Write-Host ('  restart fingerprint {0}' -f $restartFingerprint)
        $restartResponse = Invoke-Rcon $rcon ([ref] $requestId) 'jeieditor import declared-restart-fixture' `
            -RetryResponseRegex 'another recipe reload is in progress'
        if ($restartResponse -match '(?i)failed') {
            throw ("The patch that only fits the edited recipe was refused after the restart: " + $restartResponse)
        }
        [void] (Wait-ForCondition -TimeoutSeconds 30 -Condition {
                if (-not (Test-Path -LiteralPath $restartPath -PathType Leaf)) { return $false }
                ((Get-Content -LiteralPath $restartPath -Raw) | ConvertFrom-Json).result.count -eq 5
            })
        $restarted = Get-Content -LiteralPath $restartPath -Raw | ConvertFrom-Json
        if ($restarted.result.id -ne 'minecraft:lapis_lazuli' -or $restarted.result.count -ne 5 -or
                $restarted.cookingtime -ne 300 -or $restarted.group -ne 'berry_dyes' -or
                $restarted.ingredient.item -ne 'cobblemon:passho_berry') {
            throw ("The restarted server did not hold the edited recipe: " +
                (Get-Content -LiteralPath $restartPath -Raw))
        }
        Write-Host '  the edited recipe was live after the restart and accepted a further edit'
        [void] (Invoke-Rcon $rcon ([ref] $requestId) 'reload')
        Start-Sleep -Seconds 2
        if ((Get-Content -LiteralPath $restartPath -Raw | ConvertFrom-Json).result.count -ne 5) {
            throw 'The /reload after the restart lost the restart cycle edit'
        }
        Write-Host '  the /reload after the restart kept it'
    }

    Write-Host 'Stopping the server...'
    [void] (Invoke-Rcon $rcon ([ref] $requestId) 'stop')
    Close-Rcon $rcon
    $rcon = $null
    [void] $serverProcess.WaitForExit(60000)
    if (-not $serverProcess.HasExited) {
        throw "Server did not stop; logs: $logDirectory"
    }
    $serverProcess.Dispose()
    $serverProcess = $null

    Write-Host ("Declared smoke passed: {0} declared recipes rewritten and preserved, {1} probe(s) behaved as asserted" -f $cases.Count, $probeCases.Count)
} finally {
    if ($rcon -ne $null) {
        try { [void] (Invoke-Rcon $rcon ([ref] $requestId) 'stop' -MaxAttempts 2) } catch { Write-Warning "Could not stop server: $($_.Exception.Message)" }
        Close-Rcon $rcon
    }
    if ($serverProcess -ne $null) {
        [void] $serverProcess.WaitForExit(30000)
        if (-not $serverProcess.HasExited) {
            # Stop the whole tree, not just the Gradle wrapper: the game server is a
            # grandchild, and leaving it alive would keep RCON's port and the run
            # directory busy for the next run.
            & taskkill.exe /PID $serverProcess.Id /T /F | Out-Null
            [void] $serverProcess.WaitForExit(30000)
        }
        $serverProcess.Dispose()
    }
    if (Test-Path -LiteralPath $policyBackupPath -PathType Leaf) {
        Copy-Item -LiteralPath $policyBackupPath -Destination $policyConfigPath -Force
    } elseif (-not $policyWasPresent) {
        Remove-Item -LiteralPath $policyConfigPath -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $worldDirectory -PathType Container) {
        Start-Sleep -Seconds 2
        Remove-Item -LiteralPath $worldDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
    foreach ($hidden in $hiddenJars) {
        if (Test-Path -LiteralPath $hidden -PathType Leaf) {
            Move-Item -LiteralPath $hidden -Destination ($hidden -replace '\.server-smoke-disabled$', '') -Force
        }
    }
}
exit 0
