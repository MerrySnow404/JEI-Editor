# Runs JEI Editor's client recipe page tests.
#
# The test body is a development-only second mod (src/clientTest, mod id
# "jeieditortests") that only the Gradle "clientTest" run loads. It creates a
# throwaway world - JEI only registers its recipe categories after a world join -
# then walks every JEI recipe page and writes build/client-test/report.json.
# This script parses the wired page list out of the compatibility checklist,
# starts that run, waits for the report, stops the client and judges the report.
#
# Exit codes: 0 = the report says passed, 1 = the report lists failures,
#             2 = no report was produced (the run itself failed).
[CmdletBinding()]
param(
    [int] $TimeoutSeconds = 1800,
    # JDK 21 that runs Gradle. Defaults to JAVA_HOME, then to the path this repo
    # was developed on, so the script is not machine-locked.
    [string] $JdkPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Windows.Forms

$repoRoot = Split-Path -Parent $PSScriptRoot
$targetDirectory = Join-Path $repoRoot 'targets\neoforge-1.21.1'
$wrapper = Join-Path $targetDirectory 'gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw "Gradle wrapper not found: $wrapper"
}

$outputDirectory = Join-Path $targetDirectory 'build\client-test'
$wiredPath = Join-Path $outputDirectory 'wired-pages.txt'
$reportPath = Join-Path $outputDirectory 'report.json'
$progressPath = Join-Path $outputDirectory 'progress.txt'
$logDirectory = Join-Path $repoRoot 'build\tmp\client-test'
$stdoutPath = Join-Path $logDirectory 'client.stdout.log'
$stderrPath = Join-Path $logDirectory 'client.stderr.log'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

# The JDK that runs Gradle. The target builds with JDK 21.
$jdk = if ($JdkPath) { $JdkPath } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\program\jdk-21' }
if (-not (Test-Path -LiteralPath $jdk -PathType Container)) {
    throw "JDK 21 was not found at '$jdk'; pass -JdkPath or set JAVA_HOME to a JDK 21 installation."
}
$env:JAVA_HOME = $jdk

# --- the wired page list, taken from the checklist --------------------------
#
# docs/JEI_PAGE_COMPAT_TODO.md is the single source of truth: the "0" group
# table lists the vanilla pages (status "done") and every row marked "declared"
# in the A1 and B tables is a declared mod page. Rows marked "gap" are pages
# that are known not to be modelable; they are asserted the other way round
# (zero models) further down.
$checklistPath = Join-Path $repoRoot 'docs\JEI_PAGE_COMPAT_TODO.md'
if (-not (Test-Path -LiteralPath $checklistPath -PathType Leaf)) {
    throw "The page checklist was not found: $checklistPath"
}
$wired = New-Object System.Collections.Generic.List[string]
$gaps = New-Object System.Collections.Generic.List[string]
foreach ($line in Get-Content -LiteralPath $checklistPath) {
    if (-not $line.StartsWith('|')) { continue }
    $cells = @($line.Trim().Trim('|').Split('|') | ForEach-Object { $_.Trim() })
    if ($cells.Count -lt 3) { continue }
    if ($cells[0] -notmatch '^`([a-z0-9_.\-]+:[a-z0-9_/\.\-]+)`$') { continue }
    $uid = $Matches[1]
    $status = $cells[$cells.Count - 1]
    if ($status -eq 'gap') {
        if (-not $gaps.Contains($uid)) { $gaps.Add($uid) }
        continue
    }
    if ($status -ne 'done' -and $status -ne 'declared') { continue }
    if (-not $wired.Contains($uid)) { $wired.Add($uid) }
}
if ($wired.Count -eq 0) {
    throw "No wired pages could be read from $checklistPath"
}
$wiredLines = @('# Wired recipe pages, generated from docs/JEI_PAGE_COMPAT_TODO.md by scripts/jei-client-tests.ps1')
$wiredLines += $wired
Set-Content -LiteralPath $wiredPath -Value $wiredLines -Encoding ascii
Write-Host ("Wired pages from the checklist: {0}" -f $wired.Count)
foreach ($uid in $wired) { Write-Host ("  {0}" -f $uid) }
Write-Host ("Gap pages from the checklist: {0}" -f $gaps.Count)
foreach ($uid in $gaps) { Write-Host ("  {0}" -f $uid) }

Remove-Item -LiteralPath $reportPath, $progressPath -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue

Write-Host 'Starting the clientTest run (this takes several minutes)...'
$process = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList @('/d', '/c', "`"$wrapper`" runClientTest --console plain --no-daemon") `
    -WorkingDirectory $targetDirectory -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath -PassThru

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$failed = $false
$reported = $false
$dismissed = $false
while ([DateTime]::UtcNow -lt $deadline) {
    if (Test-Path -LiteralPath $reportPath -PathType Leaf) { $reported = $true; break }
    if ($process.HasExited) { break }
    if (Test-Path -LiteralPath $stdoutPath -PathType Leaf) {
        $tail = Get-Content -LiteralPath $stdoutPath -Tail 80 -ErrorAction SilentlyContinue
        if ($tail -match 'Failed to start the minecraft game|Missing or unsupported mandatory dependencies|Incompatible mod set|Failed to create mod container') {
            $failed = $true
            Start-Sleep -Seconds 8
            break
        }
        if ($tail -match 'Conflicts between mods' -and -not $dismissed) {
            $dismissed = $true
            Start-Sleep -Seconds 6
            foreach ($key in '{ENTER}', '{TAB}', '{ENTER}') {
                [void][System.Windows.Forms.SendKeys]::SendWait($key)
                Start-Sleep -Milliseconds 700
            }
        }
    }
    if (Test-Path -LiteralPath $progressPath -PathType Leaf) {
        Write-Host ("progress: " + ((Get-Content -LiteralPath $progressPath -Raw) -replace "`r?`n", ' '))
    }
    Start-Sleep -Seconds 10
}

if ($reported) { Start-Sleep -Seconds 3 }
$exited = $process.WaitForExit(30000)
if (-not $exited) {
    Write-Host 'Stopping the client process tree.'
    & taskkill.exe /PID $process.Id /T /F | Out-Null
}
if ($reported) {
    Write-Host "Report written: $reportPath"
} else {
    Write-Host 'NO REPORT PRODUCED'
}
if ($failed) { Write-Host 'MOD LOAD FAILURE DETECTED' }

if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
    Write-Host "The client did not produce a report; inspect $stdoutPath and $stderrPath"
    if (Test-Path -LiteralPath $stdoutPath -PathType Leaf) {
        Write-Host '--- last 40 log lines ---'
        Get-Content -LiteralPath $stdoutPath -Tail 40 | ForEach-Object { Write-Host $_ }
    }
    exit 2
}

# --- judge the report ------------------------------------------------------
$report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
$rows = @($report.pages)
$rowsByUid = @{}
foreach ($row in $rows) { $rowsByUid[$row.uid] = $row }

# Pages that must produce no editor model at all. "gap" rows come from the
# checklist; the three tag pages are locked here rather than read from the
# document, because JEI serves them as bare
# mezz.jei.library.plugins.jei.tags.TagInfoRecipe objects - neither a
# RecipeHolder nor a Recipe - which no editor path accepts, so they are asserted
# here as well as in the document, which marks them `gap` too. A page in both
# lists is simply required to model nothing twice, which is harmless.
#
# This is the honest end state, not a missing feature: a tag page displays a
# tag's member list (its input slot and its output slot show the same members),
# which is tag data read from the `tags/` registries, not a recipe record - so
# there is no recipe edit to make, and "editing" it would mean rewriting a tag,
# i.e. changing every recipe that uses that tag.
$knownUnsupported = @(
    'minecraft:tag_recipes/item',
    'minecraft:tag_recipes/block',
    'minecraft:tag_recipes/fluid'
)
$zeroModelRows = New-Object System.Collections.Generic.List[object]
foreach ($uid in $gaps) {
    $zeroModelRows.Add([pscustomobject] @{ uid = $uid; kind = 'gap' })
}
foreach ($uid in $knownUnsupported) {
    $zeroModelRows.Add([pscustomobject] @{ uid = $uid; kind = 'known unsupported' })
}

$gapSet = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($uid in $gaps) { [void] $gapSet.Add($uid) }
$unsupportedSet = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($uid in $knownUnsupported) { [void] $unsupportedSet.Add($uid) }

# A page recorded as not editable must stay that way. If it starts modelling,
# someone fixed it and the document (and this script's locked list) has to move.
$scriptFailures = New-Object System.Collections.Generic.List[string]
foreach ($entry in $zeroModelRows) {
    $label = $entry.kind.ToUpper()
    if ($wired.Contains($entry.uid)) {
        $scriptFailures.Add(("[CONFLICT] {0} is listed as wired and as {1} at the same time; fix the checklist or the locked list in this script" -f $entry.uid, $entry.kind))
        continue
    }
    if (-not $rowsByUid.ContainsKey($entry.uid)) {
        $scriptFailures.Add(("[{0}] {1} is not registered in JEI at all, so its zero-model record cannot be measured" -f $label, $entry.uid))
        continue
    }
    $models = [int] $rowsByUid[$entry.uid].models
    if ($models -gt 0) {
        $sampleText = ''
        $samples = @($rowsByUid[$entry.uid].modelled_samples)
        if ($samples.Count -gt 0) { $sampleText = ('; models seen: ' + ($samples -join ' | ')) }
        $scriptFailures.Add(("[{0}] {1} produced {2} model(s) but is listed as a {3}, i.e. as not editable; it now models, so update docs/JEI_PAGE_COMPAT_TODO.md (and the locked list in this script){4}" -f
            $label, $entry.uid, $models, $entry.kind, $sampleText))
    }
}

Write-Host ''
Write-Host ("Kind: {0}   pages: {1}   wired pages: {2}   gap pages: {3}   known unsupported: {4}   sample limit: {5}   walk: {6} ms   mods loaded: {7}" -f
    $report.report_version, $report.page_count, $wired.Count, $gaps.Count, $knownUnsupported.Count,
    $report.sample_limit, $report.walk_millis, $report.mods_loaded)
$missingMods = @($report.missing_test_mods)
if ($missingMods.Count -gt 0) {
    Write-Host ("WARNING: the test pack is incomplete, missing mods: {0}" -f ($missingMods -join ', '))
}

Write-Host ''
Write-Host ("{0,-46} {1,7} {2,7} {3,-34} {4}" -f 'page uid', 'sampled', 'models', 'slot keys', 'note')
Write-Host ('-' * 120)
$modelled = 0
$outputChecked = 0
$outputOk = 0
$outputSkipped = 0
$outputRefused = 0
$inputChecked = 0
$inputOk = 0
$inputSkipped = 0
# Fuel entries are the input-side counterpart of an empty output slot: their item is
# their identity, so an item swap must be refused rather than patched.
$inputRefused = 0
foreach ($row in $rows) {
    $keys = @($row.slot_key_shapes)
    $keyText = if ($keys.Count -eq 0) { '-' } else { ($keys -join ' | ') }
    $note = @()
    if ($row.wired) {
        $note += 'wired'
    } elseif ($gapSet.Contains($row.uid)) {
        $note += 'GAP'
    } elseif ($unsupportedSet.Contains($row.uid)) {
        $note += 'KNOWN UNSUPPORTED'
    } elseif ($row.models -gt 0) {
        $note += 'NOT WIRED'
    }
    if ($row.models -eq 0) {
        $note += ("no model ({0} unmodelable, {1} layout failures)" -f $row.unmodelable, $row.layout_failures)
    }
    if (@($row.patch_issues).Count -gt 0) { $note += ("{0} patch issue(s)" -f @($row.patch_issues).Count) }
    if (@($row.errors).Count -gt 0) { $note += ("{0} error(s)" -f @($row.errors).Count) }
    $modelled += $row.models
    $roundTrip = $row.patch_roundtrip
    $outputChecked += $roundTrip.output_checked
    $outputOk += $roundTrip.output_ok
    $outputSkipped += $roundTrip.output_skipped_no_slot
    $outputRefused += $roundTrip.output_refused_empty_slot
    $inputChecked += $roundTrip.input_checked
    $inputOk += $roundTrip.input_ok
    $inputSkipped += $roundTrip.input_skipped_no_slot
    $inputRefused += $roundTrip.input_refused_fuel
    Write-Host ("{0,-46} {1,7} {2,7} {3,-34} {4}" -f
        $row.uid, $row.recipes_sampled, $row.models, $keyText, ($note -join ', '))
}
Write-Host ('-' * 120)
Write-Host ("Pages: {0}   models built: {1}" -f $rows.Count, $modelled)
Write-Host ("Patch round trip: output required {0} (ok {1}, failed {2}) | empty output refused {3} | no output slot skipped {4} | input required {5} (ok {6}, failed {7}) | fuel input refused {8} | no input slot skipped {9}" -f
    $outputChecked, $outputOk, ($outputChecked - $outputOk), $outputRefused, $outputSkipped,
    $inputChecked, $inputOk, ($inputChecked - $inputOk), $inputRefused, $inputSkipped)
Write-Host ("Gate coverage: {0} wired page(s) asserted to model, {1} gap page(s) and {2} known-unsupported page(s) asserted to model nothing" -f
    $wired.Count, $gaps.Count, $knownUnsupported.Count)

$failures = @($report.failures)
if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host ("FAILURES ({0}):" -f $failures.Count)
    foreach ($failure in $failures) {
        Write-Host ("  [{0}] {1}" -f $failure.assertion, $failure.uid)
        Write-Host ("       {0}" -f $failure.detail)
    }
}

if ($scriptFailures.Count -gt 0) {
    Write-Host ''
    if ($failures.Count -eq 0) { Write-Host ("GATE FAILURES ({0}):" -f $scriptFailures.Count) }
    foreach ($failure in $scriptFailures) { Write-Host ("  {0}" -f $failure) }
}

$unexpected = @($report.unexpected_pages)
if ($unexpected.Count -gt 0) {
    Write-Host ''
    Write-Host 'PAGES THE EDITOR MATCHES BUT THAT ARE NOT WIRED (reported, not a failure):'
    foreach ($entry in $unexpected) { Write-Host ("  {0}" -f $entry) }
}

$roundTripIssues = @($report.patch_roundtrip_issues)
if ($roundTripIssues.Count -gt 0) {
    Write-Host ''
    Write-Host 'PATCH ROUND TRIP ISSUES ON NON-WIRED PAGES (reported, not a failure):'
    foreach ($issue in $roundTripIssues) {
        Write-Host ("  [{0}] {1}: {2}" -f $issue.assertion, $issue.uid, $issue.detail)
    }
}

Write-Host ''
$gateFailed = ($report.passed -ne $true) -or ($failures.Count -gt 0) -or ($scriptFailures.Count -gt 0)
if (-not $gateFailed) {
    Write-Host ("Client recipe page tests PASSED ({0} pages, {1} models; {2} wired, {3} gap and {4} known-unsupported rows asserted)." -f
        $rows.Count, $modelled, $wired.Count, $gaps.Count, $knownUnsupported.Count)
    exit 0
}
Write-Host ("Client recipe page tests FAILED ({0} in-client failure(s), {1} gate failure(s))." -f
    $failures.Count, $scriptFailures.Count)
exit 1
