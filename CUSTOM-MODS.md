# Kilowallet Custom Modifications

This branch carries a small set of local changes on top of upstream Kilowallet.

## 1. Android foreground-service compatibility

Commit:
721393e custom: use specialUse foreground services

Changes:
- Removes FOREGROUND_SERVICE_DATA_SYNC.
- Keeps FOREGROUND_SERVICE_SPECIAL_USE.
- WatchService uses foregroundServiceType="specialUse".
- CoinjoinService uses foregroundServiceType="specialUse".
- Preserves the special-use subtype descriptions.

Reason:
Android 15 imposes a time limit on dataSync foreground services. WatchService previously crashed after exhausting that allowance. specialUse avoids that timeout for the user-requested continuous wallet watcher.

## 2. Reliable transaction notifications

Commit:
9e59526 custom: reliable per-transaction notifications

Files:
- app/src/main/java/com/kilombino/pyblockwatch/data/Notifier.kt
- app/src/main/java/com/kilombino/pyblockwatch/data/Store.kt

Changes:
- Persistent txid + phase notification dedupe.
- Separate mempool and first-confirmation phases.
- Persists known transaction IDs.
- Detects transactions first seen already confirmed.
- Uses exact signed per-transaction wallet amounts from Scanner.
- Supports exact incoming and outgoing amounts.
- Retries newly discovered transactions whose amount was temporarily unavailable.
- Avoids aggregate wallet-balance-delta guessing.

## 3. Unused receive-address picker

Commit:
60d70d2 custom: add unused receive-address picker

File:
- app/src/main/java/com/kilombino/pyblockwatch/ui/HotWallet.kt

Changes:
- Adds CHOOSE UNUSED ADDRESS to Receive.
- Shows unused receive indexes in a scrollable chooser.
- Selecting an entry switches Receive to that exact unused address.
- Existing PREVIOUS / NEXT behavior remains intact.

## 4. Blue-only XBT interface

Commit:
0741d09 custom: make XBT interface blue-only

Files:
- app/src/main/java/com/kilombino/pyblockwatch/chain/Chain.kt
- app/src/main/java/com/kilombino/pyblockwatch/ui/MainActivity.kt
- app/src/main/java/com/kilombino/pyblockwatch/ui/Theme.kt

Changes:
- XBT/BLAKE2b accent is #278BF5.
- Palette defaults to and remains blue.
- Removes Purple / Blue chooser from Settings.
- Main XBT chain accent is 0xFF278BF5.

## 5. Windows build support

Commit:
0bbaaf9 build: add Windows wrapper and aapt2 verification

Changes:
- Adds gradlew.bat.
- Adds SHA-256 verification metadata for the Windows aapt2 artifact.

## Upgrade procedure

For a future upstream release:

1. Fetch upstream tags.
2. Create a new branch from the desired upstream tag.
3. Cherry-pick the custom commits in this order:
   - 721393e
   - 9e59526
   - 60d70d2
   - 0741d09
   - 0bbaaf9
4. Resolve conflicts one commit at a time.
5. Run verify-custom-mods.ps1.
6. Build with:
   .\gradlew.bat :app:assembleDebug
7. Verify the APK manifest and signer.
8. Install with adb install -r.
9. Runtime-check WatchService and transaction notifications.

Known-good baseline:
upstream v1.0.8 + the five custom commits above.
