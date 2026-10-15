$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$manifest = Join-Path $root "app\src\main\AndroidManifest.xml"
$chain    = Join-Path $root "app\src\main\java\com\kilombino\pyblockwatch\chain\Chain.kt"
$hot      = Join-Path $root "app\src\main\java\com\kilombino\pyblockwatch\ui\HotWallet.kt"
$main     = Join-Path $root "app\src\main\java\com\kilombino\pyblockwatch\ui\MainActivity.kt"
$theme    = Join-Path $root "app\src\main\java\com\kilombino\pyblockwatch\ui\Theme.kt"
$notifier = Join-Path $root "app\src\main\java\com\kilombino\pyblockwatch\data\Notifier.kt"
$store    = Join-Path $root "app\src\main\java\com\kilombino\pyblockwatch\data\Store.kt"

function Require-Text($path, $pattern, $label) {
    $text = [IO.File]::ReadAllText($path)
    if ($text -notmatch $pattern) {
        throw "FAIL: $label"
    }
    Write-Host "PASS: $label"
}

function Reject-Text($path, $pattern, $label) {
    $text = [IO.File]::ReadAllText($path)
    if ($text -match $pattern) {
        throw "FAIL: $label"
    }
    Write-Host "PASS: $label"
}

Require-Text $manifest 'FOREGROUND_SERVICE_SPECIAL_USE' 'specialUse permission present'
Reject-Text  $manifest 'FOREGROUND_SERVICE_DATA_SYNC' 'dataSync permission absent'
Require-Text $manifest 'foregroundServiceType="specialUse"' 'services use specialUse'

Require-Text $chain 'accent = 0xFF278BF5' 'XBT chain accent is #278BF5'

Require-Text $theme 'current = BLUE' 'palette forced to blue'
Reject-Text  $main 'Text\("PURPLE"' 'purple settings option removed'
Reject-Text  $main 'Palette\.choose\(activity, false\)' 'purple selector logic removed'

Require-Text $hot 'CHOOSE UNUSED ADDRESS' 'unused receive-address picker present'
Require-Text $hot 'choosingUnused' 'unused-address picker state present'

Require-Text $notifier 'pyblockwatch_txid_dedupe' 'persistent notification dedupe present'
Require-Text $notifier 'shouldSuppressTx' 'txid phase dedupe logic present'
Require-Text $store 'knownTxids' 'known transaction tracking present'
Require-Text $store 'known_txids_' 'known txid persistence key present'

Write-Host ""
Write-Host "All custom-mod checks passed."
