# Kilowallet Custom Modifications

This repository carries a small set of local changes on top of upstream Kilowallet.

## Current known-good custom release

Upstream:
- v1.1.0
- upstream commit: 31c181e

Known-good custom tag:
- custom-v1.1.0-known-good

Current 1.1.0 custom commits:
- 8e9c8fc custom: use specialUse foreground services
- c40e01a custom: reliable per-transaction notifications
- ff1fd3d custom: add unused receive-address picker
- 0c1c24f custom: make XBT interface blue-only
- 360cbfa build: add Windows wrapper and aapt2 verification
- 833adc5 docs: document and verify custom modifications

Previous known-good release:
- custom-v1.0.8-known-good

Original 1.0.8 custom commits:
- 721393e custom: use specialUse foreground services
- 9e59526 custom: reliable per-transaction notifications
- 60d70d2 custom: add unused receive-address picker
- 0741d09 custom: make XBT interface blue-only
- 0bbaaf9 build: add Windows wrapper and aapt2 verification
- 90d512b docs: document and verify custom modifications

## 1. Android foreground-service compatibility

Purpose:
- Removes FOREGROUND_SERVICE_DATA_SYNC.
- Keeps FOREGROUND_SERVICE_SPECIAL_USE.
- WatchService uses foregroundServiceType="specialUse".
- CoinjoinService uses foregroundServiceType="specialUse".
- Preserves the special-use subtype descriptions.

Reason:
Android 15 imposes a time limit on dataSync foreground services. WatchService previously crashed after exhausting that allowance. specialUse avoids that timeout for the user-requested continuous wallet watcher.

## 2. Reliable transaction notifications

Files:
- app/src/main/java/com/kilombino/pyblockwatch/data/Notifier.kt
- app/src/main/java/com/kilombino/pyblockwatch/data/Store.kt

Purpose:
- Persistent txid + phase notification dedupe.
- Separate mempool and first-confirmation phases.
- Persists known transaction IDs.
- Detects transactions first seen already confirmed.
- Uses exact signed per-transaction wallet amounts from Scanner.
- Supports exact incoming and outgoing amounts.
- Retries newly discovered transactions whose amount was temporarily unavailable.
- Avoids aggregate wallet-balance-delta guessing.

## 3. Unused receive-address picker

File:
- app/src/main/java/com/kilombino/pyblockwatch/ui/HotWallet.kt

Purpose:
- Adds CHOOSE UNUSED ADDRESS to Receive.
- Shows unused receive indexes in a scrollable chooser.
- Selecting an entry switches Receive to that exact unused address.
- Existing PREVIOUS / NEXT behavior remains intact.

## 4. Blue-only XBT interface

Files:
- app/src/main/java/com/kilombino/pyblockwatch/chain/Chain.kt
- app/src/main/java/com/kilombino/pyblockwatch/ui/MainActivity.kt
- app/src/main/java/com/kilombino/pyblockwatch/ui/Theme.kt

Purpose:
- XBT/BLAKE2b accent is #278BF5.
- Palette defaults to and remains blue.
- Removes Purple / Blue chooser from Settings.
- Main XBT chain accent is 0xFF278BF5.

## 5. Windows build support

Purpose:
- Adds gradlew.bat.
- Adds SHA-256 verification metadata for the Windows aapt2 artifact.

## Upgrade procedure for future upstream releases

For a future upstream release:

1. Fetch upstream tags:
   git fetch origin --tags

2. Create a new custom branch from the desired upstream tag.

3. Reapply the custom features in this order:
   - specialUse foreground services
   - reliable per-transaction notifications
   - unused receive-address picker
   - blue-only XBT interface
   - Windows build support
   - maintenance documentation / verification script

4. Prefer cherry-picking the most recent known-good custom commit set when it applies cleanly.

5. Resolve conflicts one feature at a time.

6. Run:
   .\verify-custom-mods.ps1

7. Build with:
   .\gradlew.bat :app:assembleDebug

8. Verify:
   - package/version
   - APK signer
   - specialUse-only manifest
   - blue XBT UI
   - unused receive-address picker
   - wallet/server settings preserved
   - Fulcrum endpoint preserved
   - WatchService runtime type = 0x40000000

9. Install in place with:
   adb install -r

10. After validation:
   - tag the release as custom-vX.Y.Z-known-good
   - push the custom branch and tag to the private custom remote

## Verification

Run:

.\verify-custom-mods.ps1

A valid custom build should report that all checks passed.
