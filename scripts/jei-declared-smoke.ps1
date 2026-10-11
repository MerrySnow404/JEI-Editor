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
    foreach ($jar in Get-ChildItem -LiteralPath $modsDirectory -Filter '*.jar' -File) {
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
    # is rewritten, the result and the untouched keys survive. The same serializer's fluid
    # shape (fluidResult + inputCount) is deliberately not declared.
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
    # silentgear:gear_crafting is Silent Gear's GearCraftingRecipeCategoryJei page. Its
    # modelable recipes are silentgear:compound_part ones (the rest of the sampled 40 carry
    # silentgear:gear_crafting). CompoundPartRecipe extends ShapelessRecipe, so it used to
    # be modelled as a crafting grid even though its ingredients are Silent Gear's own
    # {"type":"silentgear:blueprint"/"material"} forms; the server never accepted the edit
    # for it (the import answered "stale or unsupported patch"), so the client was offering
    # a slot whose patch the server refused. With the adapter gated on the two vanilla
    # crafting serializers the page no longer models at all, and the patch is refused by the
    # same pre-write gate - nothing is written.
    # data/silentgear/recipe/gear/sickle_head.json is
    #   {"type":"silentgear:compound_part","category":"equipment",
    #    "ingredients":[{"type":"silentgear:blueprint",...},{"type":"silentgear:material",...} x3],
    #    "result":{"count":1,"id":"silentgear:sickle_blade"}}
    @{
        Name = 'silentgear:gear_crafting (silentgear:compound_part)'
        Bundle = 'declared-probe-silentgear'
        RecipeId = 'silentgear:gear/sickle_head'
        Serializer = 'silentgear:compound_part'
        RecipePath = 'data\silentgear\recipe\gear\sickle_head.json'
        Fields = '{"input.1.item":"minecraft:oak_planks","input.1.count":"1"}'
        BaseSlots = ('silentgear:gear/sickle_head|silentgear:compound_part|' +
            'input.0=input:item:silentgear:sickle_blueprint:1|input.1=input:item:minecraft:bamboo_planks:1|' +
            'input.2=input|input.3=input:item:minecraft:bamboo_planks:1|input.4=input:item:minecraft:bamboo_planks:1|' +
            ((5..8 | ForEach-Object { "input.$_=input" }) -join '|') +
            '|output=output:item:silentgear:sickle_blade:1')
        Written = $false
        ExpectResponse = 'Import failed: stale or unsupported patch'
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
    # create:spout_filling, 12 of its 50 entries in the manager: the page's own
    # sample (this id) is a datapack recipe and is reachable, while
    # SpoutCategory#consumeRecipes synthesises the other 38. The page is not
    # declared - a declaration would cover 12 of 50 entries - so even the
    # reachable recipe is refused by the pre-write gate and nothing is written.
    @{
        Name = 'create:spout_filling (datapack entry on an undeclared page)'
        Bundle = 'declared-probe-create-spout'
        RecipeId = 'create:filling/compat/immersiveengineering/treated_wood_in_spout'
        Serializer = 'create:filling'
        RecipePath = 'data\create\recipe\filling\compat\immersiveengineering\treated_wood_in_spout.json'
        Fields = '{"output.0.item":"minecraft:crimson_planks"}'
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
