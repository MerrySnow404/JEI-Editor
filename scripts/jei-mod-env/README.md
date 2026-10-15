# scripts/jei-mod-env

The toolchain that assembled the NeoForge 1.21.1 client/server mod pack in
`targets/neoforge-1.21.1/run/mods` — the 76 files the two harnesses
(`scripts/jei-client-tests.ps1`, `scripts/jei-declared-smoke.ps1`) load: 68 active
`*.jar` plus 8 `*.jar.disabled` that were deliberately turned off.

## Requirements

- Python 3, standard library only. No third-party packages, no `pip install`.
- PowerShell 5+ for `run-client.ps1` (Windows).
- Run the scripts from the **repository root**. The batch-1 scripts (`gather.py`,
  `probe.py`, `deps.py`) use cwd-relative literals such as
  `build/tmp/modsearch/hits.json`, so the working directory matters. The rest
  resolve the repository root from their own file location and work from anywhere.

## Pipeline

The order below is the order it was actually run in. Each stage writes JSON state
under the gitignored `build/tmp/modsearch/`.

### Batch 1 — standalone JEI addons

1. `gather.py` — Modrinth keyword search for JEI addons; writes
   `build/tmp/modsearch/hits.json`.
2. `probe.py` — which of those candidates declare JEI at all.
3. `stage.py` — downloads each candidate and reads its **real** metadata out of the
   jar into `staged.json`. Modrinth's dependency metadata is unreliable (it omits
   e.g. Just Enough Resources' JEI dependency), so classification is done from the
   jar, not from the API.
4. `deps.py` — classify the staged jars by their declared dependencies.

### Batch 2 — addons that need content mods

5. `resolve.py` — walks each jar's own declared required mod ids from its own
   `mods.toml`, maps mod id -> Modrinth project, downloads the newest 1.21.1
   NeoForge build, and persists the graph to `tree.json`.
6. `prune.py` — **the authoritative assembler.** Drops every jar whose required
   dependency cannot be satisfied against the pinned environment (`ENV` in the
   script), repeats to a fixed point, keeps only jars reachable from the surviving
   roots, then writes `run/mods` and `prune.json`.

### Runtime verification

7. `verify.py` — recursive, jar-in-jar aware dependency verifier. Also a library:
   `parse_toml` / `scan_jar` are imported by the printer scripts below.
8. `run-client.ps1` — launches the dev client with the installed pack and stops at
   the main menu or on a load failure.
9. `iterate.py` — drives `run-client.ps1`, drops jars that fail only at pre-load,
   and repeats (`removed_by_runtime.json`).

### Visibility-conflict pass (76 -> 68 active)

10. `scan_addon_api.py` — finds addons that call JEI's recipe/ingredient
    visibility API (`hideRecipes` / `unhideRecipes` / `addRecipes` and friends) and
    would fight the editor over the same state.
11. `check_disable_impact.py` — who depends on the jars about to be disabled.
12. `disable_addons.py` — renames the offenders to `*.jar.disabled`. Idempotent;
    renaming rather than deleting keeps the change reversible.

## What is reusable vs. one-off

| Script(s) | Kind |
| --- | --- |
| `gather.py`, `probe.py`, `deps.py`, `stage.py`, `resolve.py`, `prune.py`, `verify.py`, `iterate.py`, `run-client.ps1` | reusable general tooling |
| `build_pack_manifest.py`, `download_pack.py` (+ the tracked `pack-manifest.json`) | the one-command install path: manifest from an installed pack, download on a fresh clone |
| `scan_addon_api.py`, `check_disable_impact.py`, `disable_addons.py` | reusable, but the target set is the hardcoded 1.21.1 addon list in each script |
| `select2.py`, `final_assemble.py`, `resolve_more.py`, `resolve_more2.py`, `add_layer2.py`, `fetch_datanessence.py`, `pluginscan.py`, `select.py`, `install.py`, `preflight.py`, `assemble.py` | **one-off** historic steps, deliberately left behind in `build/tmp/modsearch/` |
| `search.py`, `probe2.py`, `check.py`, `bwtest.py` | dead scratch files, left behind |

The one-off steps are not optional decoration: they are needed to reproduce batch
2's exact membership (extra manual resolution rounds, a hand-assembled layer, and
the Data Essence/HalcyonJEI removal). They are **not** in the tracked tree — only in
the gitignored `build/tmp/modsearch/`, which is not recoverable from a fresh clone.

## Rebuilding `run/mods` on a fresh clone

`run/` and `build/` are gitignored, so a fresh clone starts with neither the installed
pack nor any of the state under `build/tmp/modsearch/`. Two scripts here install it in
one step each:

```powershell
python scripts/jei-mod-env/download_pack.py    # installs the 76 files, missing ones only
python scripts/jei-mod-env/verify.py           # expect the first line: jars: 68
```

`download_pack.py` reads **`pack-manifest.json`** (tracked, 76 entries, ~241 MiB in
total): for every installed file it records the Modrinth project, version, download URL
and the `sha1`/`sha512` the file must have, plus whether the file is one of the 8 that
stay disabled. Downloads are hashed and compared against the manifest, so the script is
idempotent and re-running it is cheap; `--dry-run` only reports, `--force` replaces a
file whose hash disagrees.

`pack-manifest.json` is generated by **`build_pack_manifest.py`** on a machine that
still has an installed pack: it hashes each jar and asks Modrinth's
`/v2/version_files` which version that exact file belongs to, so nothing — project
slug, file name, dependency graph — has to be guessed. It also cross-checks the manifest
against the 76-name list at the bottom of this file and fails if the two disagree.

`verify.py` always ends by reporting `jei` as a missing required dependency and exits
non-zero, because JEI itself is **not** part of the pack: the harnesses provide it from
the build classpath. The line that matters is the first one (`jars: 68   distinct mod ids
provided: 101`) and the absence of any *other* missing mod.

### Why there is no producer for `report.json`

This subsection is the original reason the pack had no one-command rebuild, kept because
it explains what `report.json` and `build/tmp/modsearch/jars/` are for. The manifest route
above exists precisely because this pipeline cannot be replayed: it needs two inputs no
tracked script produces.

`prune.py` — the authoritative assembler — needs two inputs that no tracked script
produces:

1. `build/tmp/modsearch/report.json`. `prune.py` reads exactly one field of it,
   `report.json["keep"]`, and from each entry only `entry["staged"]` — a path whose
   **basename must match a file in `build/tmp/modsearch/jars/`**, that is
   `build/tmp/modsearch/jars/<slug>__<Modrinth-filename>.jar`. No script here writes
   that file; it was written ad hoc during the original run. Its key data is not in the
   tracked docs: the docs give display versions, not Modrinth file names, and the two
   differ — e.g. doc `1.4.5+neoforge-1.21.1` vs file
   `jeiworldgen-neoforge-1.21.1-1.4.5.jar`, doc `2.0.0` vs file
   `[1.21.1] neoforge-JECT-2.0.0.jar`.
2. `build/tmp/modsearch/jars/` — 221 jars on the machine that built the pack, obtained by
   network search + download (`gather.py`/`stage.py` for batch 1, `resolve.py` for batch
   2). `prune.py` walks this directory to build its `info` map and copies the kept jars
   out of it; with the directory missing it raises `FileNotFoundError`, and with it empty
   it raises `KeyError` while classifying — either way it installs nothing.

Because `prune.py` matches `staged` basenames against that directory, a producer that
guessed names from the docs would silently miss every batch-1 jar. So the gap is not
just "the file is missing"; the data needed to regenerate it faithfully comes from the
network, not from tracked files. No producer has been invented for it.

The exact membership also depended on tracked-and-gone one-off steps — `resolve_more.py`,
`resolve_more2.py`, `final_assemble.py`, `select2.py`, `add_layer2.py`,
`fetch_datanessence.py`, … — and the batch-1 search is not pinned: `gather.py` takes its
keywords as command-line arguments (the original 20 are not recorded anywhere) and every
`pick_version` takes the newest build at run time, which drifts as Modrinth is updated.

### The tracked docs are not a complete manifest either

Downloading exactly what the three docs list does **not** reproduce `run/mods`:

- `just-enough-beacons-reforged` is installed but is missing from
  `JEI_ADDONS_CONTENT_MODS.md`'s "已安装的附属（15）" table.
- `sophisticated-core` and `sophisticated-backpacks` are installed but appear only as
  bare slugs in a prose list in `DISABLED_JEI_ADDONS.md` — no version, no install table.
- The "连带安装的前置模组（23）" table marks `revelationary`, `anvilcraft` and `emi` as
  已剔除: they are rows in the table but must **not** be installed.

### By-hand rebuild (fallback, when Modrinth is unreachable)

1. Download the 76 files listed below from their Modrinth project pages into
   `targets/neoforge-1.21.1/run/mods/`, keeping the exact file names — the docs and the
   tooling refer to them by name, and `pack-manifest.json` carries the URL and hash of
   each one.
2. Leave the 8 files under "Disabled" as `*.jar.disabled`; NeoForge only loads `*.jar`.
   If they arrive as `.jar`, `python scripts/jei-mod-env/disable_addons.py` renames all 8
   in one idempotent pass.
3. Check the result: `python scripts/jei-mod-env/verify.py` should print
   `jars: 68   distinct mod ids provided: 101` on its first line. It then reports `jei`
   as a missing required dependency and exits 1 — expected, JEI comes from the run
   classpath — so judge the pack by the jar count and by whether any mod other than
   `jei` is listed as missing.

The batch-1 network walk still runs — from the repository root: `gather.py <keywords>`,
then `probe.py`, then `stage.py --all-candidates`, then `deps.py` — but it does not
produce `report.json`, so it cannot feed `prune.py`. Treat its output as advisory
research only.

### The 76 target files

Captured from the working-tree `targets/neoforge-1.21.1/run/mods/`. Because `run/` is
gitignored, this list is the only complete record of the set.

Active (68):

```text
advanced-core-info__AdvancedCoreInfo-neoforge-1.21.1-1.3.0.jar
advanced-loot-info__AdvancedLootInfo-neoforge-1.21.1-2.3.0.jar
advanced-worldgen-info__AdvancedWorldgenInfo-neoforge-1.21.1-1.2.0.jar
ae2__appliedenergistics2-19.2.18.jar
ae2utility__ae2utility-1.8.0.jar
anvil-lib__anvillib-neoforge-1.21.1-2.0.0+snapshot.546.jar
better-piglin-trades__betterpiglintrades-1.21.1-1.2.0.jar
block-detective__BlockDetective-1.21-(v.2.1.0-NEO).jar
cerbons-api__CerbonsAPI-NeoForge-1.21-1.3.0.jar
chest-helper__chesthelper-neoforge-1.21.1-1.0.16.jar
cobbledex-rei-emi-jei__cobbledex-rei-emi-jei-neoforge-2.28.8.jar
cobblemon__Cobblemon-neoforge-1.8.1+1.21.1.jar
crafting-station-jei-edition-updated__craftingstationjei-1.21.1-NeoForge-2.2.0.jar
create-jei-compat__createjeicompat-1.0.3.jar
create-just-filter-stamps__filter_stamp-1.0.0.jar
create-redstone-link-gui__createredstonelinkgui-1.21.1-1.9.2.jar
create-satisfied__createsatisfied-0.1.2.jar
create__create-1.21.1-6.0.10.jar
curios__curios-neoforge-9.5.1+1.21.1.jar
fast-pipes__fastpipes-1.21.1-1.3.9.jar
guideme__guideme-21.1.19.jar
horse-powered__horsepowered-2.7.3.jar
immersiveengineering__ImmersiveEngineering-1.21.1-12.4.2-194.jar
irequesting__irequesting-0.1.2.jar
jackittome__JackItToMe-neoforge-1.21.1-1.0.0.jar
jakes-build-tools-emijeicreative-compatibility__jbtviewer-neoforge-1.21.1-1.0.0.jar
jea__JustEnoughAdvancements-21.1.1.jar
jefa__jefa-1.1.0-1.21.1.jar
jei++-__jeiptweakp-1.1.0.jar
jei-bookmark-sync__jeibookmarksync-1.0.0.jar
jei-crafting-tree__jeict-1.0.0.jar
jei-multiblocks__jeimultiblocks-1.21.1-1.0.6.jar
jei-quickcraft__jei-quickcraft-1.21.1-neoforge-1.0.jar
jei-worldgen__jeiworldgen-neoforge-1.21.1-1.4.5.jar
jerb__Just-Enough-Recipe-Book-neoforge-1.21.1-1.0.0.jar
jerm__jerm-1.21.1-1.2.jar
just-enough-archaeology__jearchaeology-1.21.0-1.1.5.jar
just-enough-beacons-reforged__JustEnoughBeacons-NeoForge-1.21-1.3.0.jar
just-enough-crafting-tree__[1.21.1] neoforge-JECT-2.0.0.jar
just-enough-effect-descriptions-jeed__jeed-1.21-2.3.4-neoforge.jar
just-enough-mekanism-multiblocks__JustEnoughMekanismMultiblocks-1.21.1-7.21.jar
just-enough-professions-jep__JustEnoughProfessions-neoforge-1.21.1-4.0.5.jar
just-enough-recipe-sharing__justenoughrecipesharing-neoforge-1.21.1-1.0.2.jar
just-enough-resources-jer__JustEnoughResources-NeoForge-1.21.1-1.6.0.12.jar
just-enough-serverless-recipes__JustEnoughServerlessRecipes-neoforge-1.21.1-1.2.1.jar
justenoughbreeding__justenoughbreeding-neoforge-1.21.1-3.3.1.jar
justenoughcharacters__jecharacters-1.21.1-neoforge-4.5.29.jar
kotlin-for-forge__kotlinforforge-5.12.0-all.jar
libx__LibX-1.21.1-6.0.9.jar
mafglib__mafglib-0.4.3+mc1.21.1.jar
mekanism__Mekanism-1.21.1-10.7.19.85.jar
modonomicon__modonomicon-1.21.1-neoforge-1.120.7.jar
more-overlays-updated__moreoverlays-1.24.2-mc1.21.1-neoforge.jar
myotus-lib__Myotus-1.21.1-19.1.1.jar
nnp-easy-farming__nnp_easy_farming-1.21.1- NEOFORGE-1.2.4.jar
rack-it-up__rackitup-1.0.0.jar
refined-storage-jei-integration__refinedstorage-jei-integration-neoforge-1.0.0.jar
refined-storage__refinedstorage-neoforge-2.0.9.jar
resource-geodes-catalysts__createresourcegeodes-neoforge-1.21.1-0.3.3.jar
rhino__rhino-2101.2.7-build.85.jar
sengoku-emijei__sengoku-emi-jei-neoforge-2.0.0.jar
silent-gear__silent-gear-1.21.1-neoforge-4.2.1.1.jar
silent-lib__silent-lib-1.21.1-neoforge-10.6.0.jar
silentgearjei__silentgearjei-1.1.9.jar
smithing-template-viewer__smithingtemplateviewer-1.0.4.jar
sophisticated-backpacks__sophisticatedbackpacks-1.21.1-3.26.7.2182.jar
sophisticated-core__sophisticatedcore-1.21.1-1.5.5.2363.jar
uei__UniversalEnchantmentInfo-1.21.1-neoforge-1.4.0.jar
```

Disabled — rename to `*.jar.disabled` if downloaded as `.jar` (8):

```text
comprehension-jei-addon__gatedjei1.0.0.jar.disabled
jei-trim-hider__jei_trim_hider-1.0.2-NeoForge-1.21.1.jar.disabled
jei-unhidden__JEI Unhidden NeoForge v1.0.1 for mc1.21.1.jar.disabled
jeirecipemanager__jeirecipemanager-1.0.2.jar.disabled
kubejs-jei-info-removal__kubejs-jeiinforemover-1.0.jar.disabled
kubejs__kubejs-neoforge-2101.7.2-build.377.jar.disabled
progressivestages__progressivestages-3.0.6.jar.disabled
recipe-item-sync__recipeitemsync-1.21.1-3.1.0.jar.disabled
```

## Where the pack is documented

The assembled pack, and why each mod is in it, is described in:

- `docs/JEI_ADDONS_1.21.1_NEOFORGE.md` — batch 1
- `docs/JEI_ADDONS_CONTENT_MODS.md` — batch 2
- `docs/DISABLED_JEI_ADDONS.md` — the 8 disabled jars

Those documents spell out the historic `build\tmp\modsearch\...` paths; the scripts they
describe now live here. As noted above they are not a complete, version-pinned manifest —
the three gaps listed under "The tracked docs are not a complete manifest either" mean the
file list above, not the docs alone, is what a rebuild must follow.

## run-client.ps1 and the JDK

`run-client.ps1` needs the JDK 21 that Gradle uses. It resolves it in this order:

1. `-JdkPath <dir>` if passed,
2. an already-valid `$env:JAVA_HOME`,
3. the default `D:\program\jdk-21`.

If none of those is an existing directory the script throws. Example:

```powershell
pwsh scripts/jei-mod-env/run-client.ps1 -JdkPath 'C:\jdk-21'
```
