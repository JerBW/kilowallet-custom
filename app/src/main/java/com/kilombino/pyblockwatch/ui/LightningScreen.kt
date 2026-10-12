package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.ln.Lightning
import com.kilombino.pyblockwatch.ln.Lnd
import com.kilombino.pyblockwatch.ln.LnMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The LN tab (advanced, hot wallet): a Lightning node on the phone, LND with the neutrino light
 * client, self-custodial. Off until the user turns it on; beta, and it says so.
 */
@Composable
fun LightningScreen(vm: WalletViewModel, accent: Color, settings: Boolean) {
    val activity = LocalContext.current as FragmentActivity
    val ctx = activity.applicationContext
    val ui by vm.state.collectAsState()
    val status by Lightning.status.collectAsState()
    Lightning.chainTip = ui.current.height

    LaunchedEffect(Unit) { Lightning.resume(ctx) }
    DisposableEffect(Unit) { Lightning.watch(true); onDispose { Lightning.watch(false) } }

    // Restore, migrate and backups show with the app's own settings (the button by ⏻):
    // closed, the tab shows only the node.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = status) {
            is Lightning.Status.Off -> {
                LnIntro(accent) { onError -> turnOn(activity, vm, onError) }
                if (settings) {
                    Panel(accent = accent) {
                        SectionLabel("Restore", accent)
                        Spacer(Modifier.height(6.dp))
                        Explain("The node's keys come from this wallet's recovery words: to restore a node, restore its words as this wallet first (the usual wallet restore), then choose one of the two ways below before turning Lightning on.")
                    }
                    MoveIn(accent)
                    RestoreChannels(accent)
                }
            }
            is Lightning.Status.Starting -> Panel(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(accent); Spacer(Modifier.width(8.dp))
                    Text(s.step.replaceFirstChar { it.uppercase() } + "…", color = TextSoft)
                }
                val tip = ui.current.height
                val h = s.height
                if (h != null && tip > 0) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (h.toFloat() / tip).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = accent, trackColor = accent.copy(alpha = 0.15f))
                    Spacer(Modifier.height(4.dp))
                    Text("block ${groupSats(minOf(h, tip).toLong())} of ${groupSats(tip.toLong())}",
                        style = MaterialTheme.typography.bodySmall, color = TextSoft)
                }
                Spacer(Modifier.height(6.dp))
                Explain("The first start downloads block headers and filters from the peers (a few hundred MB, a few minutes on Wi-Fi). Later starts only catch up. You can use the other tabs meanwhile: the node keeps going while the app is open.")
            }
            is Lightning.Status.Failed -> Panel(accent = Bad) {
                Text("Lightning did not start", color = Bad, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = TextSoft)
                TextButton(onClick = { Lightning.resume(ctx) }) { Text("TRY AGAIN", color = accent) }
            }
            is Lightning.Status.Running -> LnNode(s, accent, settings)
        }
    }
}

private fun turnOn(activity: FragmentActivity, vm: WalletViewModel, onError: (String) -> Unit) {
    val cipher = vm.seedCipherOrToast() ?: return
    Biometric.authenticate(activity, "Turn on Lightning", "The node's keys come from your recovery words", cipher,
        onSuccess = { authed ->
            activity.lifecycleScope.launch {
                runCatching {
                    val seed = withContext(Dispatchers.Default) {
                        val secret = vm.revealSecret(authed)
                        Bip39.toSeed(secret.words, secret.passphrase)
                    }
                    withContext(Dispatchers.IO) { Lightning.enable(activity.applicationContext, seed) }
                }.onFailure { onError(it.message ?: "Lightning could not be turned on") }
            }
        },
        onError = onError)
}

/**
 * Coming from another phone: its channel backup file (channel.backup, or the one inside the
 * backup file, which a restore already brings) is handed to the new node, which asks each peer
 * to close the channels and gets the money back on-chain.
 */
@Composable
private fun RestoreChannels(accent: Color) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(Lightning.hasStagedScb(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { b ->
                if (b.size < 32 || b.size > 5_000_000) msg = "That does not look like a channel backup."
                else { Lightning.stageScb(ctx, b); loaded = true; msg = null }
            }.onFailure { msg = it.message }
    }
    Panel(accent = accent) {
        SectionLabel("Restore from seed + SCB (channels close)", accent)
        Spacer(Modifier.height(6.dp))
        Explain("If your channels lived on a phone that is lost or wiped, load its channel backup (channel.backup, saved from the old LN tab) before turning on. A backup file (.kab) restored at setup already carries it. The node then asks each peer to close the channels and the money comes back on-chain, in your Lightning node, after their time lock.")
        Text("Only if the old phone no longer runs Lightning. If it is still alive, the peers may punish the old state and the money is lost.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        if (loaded) Text("✓ Channel backup loaded: it is used when you turn Lightning on.", style = MaterialTheme.typography.bodySmall, color = Good)
        Text("Every channel in the backup gets closed: the money comes back on-chain, but the channels are gone.", style = MaterialTheme.typography.bodySmall, color = Warn)
        TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text(if (loaded) "LOAD ANOTHER SCB" else "LOAD SCB", color = accent) }
        msg?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LnIntro(accent: Color, onTurnOn: ((String) -> Unit) -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Panel(accent = accent) {
        SectionLabel("Lightning · beta", accent)
        Spacer(Modifier.height(8.dp))
        Text("Your own Lightning node, on this phone", style = MaterialTheme.typography.titleMedium, color = TextMain)
        Spacer(Modifier.height(6.dp))
        Explain("It runs LND for the BLAKE2b chain with neutrino: it follows the chain itself from compact block filters, checks the proof of work, and keeps the channels and their keys here. Nobody holds your money. The keys come from your recovery words.")
    }
    Panel(accent = Warn) {
        SectionLabel("Before you turn it on", Warn)
        Spacer(Modifier.height(6.dp))
        listOf(
            "BETA. Put in only what you can afford to lose.",
            "NEVER run Lightning with these words on two phones or profiles at once. Two nodes with the same keys break the channels and can lose their money. (Ark can be copied back; Lightning cannot.)",
            "The recovery words bring back the node's on-chain coins, but not the channels on their own. The backup file (Settings) carries the channel backup: save a new one after you open or close a channel. With it, a new phone asks each peer to close the channels and gets the money back on-chain.",
            "To receive, the phone must be online with the app open. Paying needs a channel with enough balance.",
            "Opening and closing a channel are on-chain transactions with their fees.",
            "A watchtower (Kilombino's, through Tor) watches your channels while the phone is off.",
            "The first start downloads a few hundred MB.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = TextSoft) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I understand, it is a beta", style = MaterialTheme.typography.bodySmall, color = TextMain)
        }
        if (Lnd.stoppedThisRun) Text("Close the app with ⏻ and open it again to turn Lightning on.", style = MaterialTheme.typography.bodySmall, color = Warn)
        Button(onClick = { error = null; onTurnOn { error = it } }, enabled = agreed && !Lnd.stoppedThisRun, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("TURN ON LIGHTNING") }
        error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }

}

private enum class LnSheet { None, Fund, Open, Receive, Pay }

@Composable
private fun LnNode(s: Lightning.Status.Running, accent: Color, settings: Boolean) {
    var sheet by remember { mutableStateOf(LnSheet.None) }
    val i = s.info
    val b = s.balances
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(if (i.syncedChain) Good else Warn); Spacer(Modifier.width(8.dp))
            val sync = if (i.syncedChain) "Synced" else "Syncing"
            val h = if (s.tip > 0 && !i.syncedChain) "block ${groupSats(i.height.toLong())} of ${groupSats(s.tip.toLong())}" else "block ${groupSats(i.height.toLong())}"
            Text("$sync · $h · ${i.peers} peer" + (if (i.peers == 1) "" else "s"), style = MaterialTheme.typography.bodySmall,
                color = TextSoft, modifier = Modifier.weight(1f))
            StreetEye(TextSoft)
        }
        Spacer(Modifier.height(10.dp))
        Text(street("${groupSats(b?.channels ?: 0)} sats"), style = MaterialTheme.typography.headlineSmall, color = accent)
        Text("in channels, can be paid with", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Spacer(Modifier.height(6.dp))
        Text("On-chain in the node: " + street(groupSats(b?.onchainConfirmed ?: 0)) + " sats" +
            ((b?.onchainUnconfirmed ?: 0).takeIf { it > 0 }?.let { " (+" + street(groupSats(it)) + " unconfirmed)" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
        if ((b?.pendingOpen ?: 0) > 0) Text("Opening: " + street(groupSats(b!!.pendingOpen)) + " sats", style = MaterialTheme.typography.bodySmall, color = Warn)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(("⛓" to "FUND") to LnSheet.Fund, ("🤝" to "CHANNEL") to LnSheet.Open,
               ("⚡" to "INVOICE") to LnSheet.Receive, ("📤" to "PAY") to LnSheet.Pay).forEach { (t, v) ->
            val (icon, name) = t
            Button(onClick = { sheet = if (sheet == v) LnSheet.None else v }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (sheet == v) accent else accent.copy(alpha = 0.25f)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(icon, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, color = if (sheet == v) Ink else TextMain)
                }
            }
        }
    }

    when (sheet) {
        LnSheet.Fund -> FundSheet(accent)
        LnSheet.Open -> OpenSheet(accent, b?.onchainConfirmed ?: 0) { sheet = LnSheet.None }
        LnSheet.Receive -> ReceiveLn(accent, i.syncedChain && s.channels.any { it.active })
        LnSheet.Pay -> { PayLn(accent) { sheet = LnSheet.None }; PayOnchain(accent) }
        LnSheet.None -> {}
    }

    if (s.pending.isNotEmpty() || s.channels.isNotEmpty()) Panel(accent = accent) {
        SectionLabel("Channels", accent)
        s.pending.forEach { p ->
            Spacer(Modifier.height(6.dp))
            Text(p.peer.take(16) + "…  " + street(groupSats(p.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text("opening" + (if (p.confirmationsLeft > 0) " · ${p.confirmationsLeft} confirmations left" else " · waiting for the first confirmation"),
                style = MaterialTheme.typography.bodySmall, color = Warn)
        }
        s.channels.forEach { c ->
            Spacer(Modifier.height(6.dp))
            val name = c.peerAlias.ifBlank { c.peer.take(16) + "…" }
            Text(name + "  " + street(groupSats(c.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text((if (c.active) "active" else "offline") + " · yours " + street(groupSats(c.local)) + " · can receive " + street(groupSats(c.remote)),
                style = MaterialTheme.typography.bodySmall, color = if (c.active) TextSoft else Warn)
        }
    }

    if (settings) {
        SaveChannelBackup(accent)
        RestoreChannelsLive(accent)
        MoveOut(accent)
        DeleteEmptyNode(accent, s)
    }

    Panel(accent = Warn) {
        Explain("Beta. Never run Lightning with these words on another phone or profile at the same time. Node: ${i.pubkey.take(20)}…")
    }
}

@Composable
private fun FundSheet(accent: Color) {
    val clip = LocalClipboardManager.current
    var addr by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { Lnd.newAddress() } }.onSuccess { addr = it }.onFailure { err = it.message }
    }
    Panel(accent = accent) {
        SectionLabel("Fund the node", accent)
        Spacer(Modifier.height(6.dp))
        Explain("Channels are opened from the node's own on-chain coins. Send to this address from the BTC tab (or anywhere), then open a channel once it confirms.")
        Spacer(Modifier.height(8.dp))
        addr?.let { a ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { QrImage("bitcoin:$a", 200) }
            Spacer(Modifier.height(6.dp))
            Text(a, style = MaterialTheme.typography.bodySmall, color = TextMain)
            TextButton(onClick = { clip.setText(AnnotatedString(a)) }) { Text("COPY", color = accent) }
        }
        err?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun OpenSheet(accent: Color, onchain: Long, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var custom by remember { mutableStateOf(true) }
    var node by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("1") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Panel(accent = accent) {
        SectionLabel("Open a channel", accent)
        Spacer(Modifier.height(6.dp))
        Explain("A channel locks some of the node's on-chain coins with one peer; what you pay goes out through it. Kilombino's node is suggested because it is well connected on this chain; any node works.")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = !custom, onClick = { custom = false }); Text("Kilombino", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.RadioButton(selected = custom, onClick = { custom = true }); Text("Another node", style = MaterialTheme.typography.bodySmall, color = TextMain)
        }
        if (custom) OutlinedTextField(node, { node = it.trim() }, label = { Text("pubkey@host:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Sats (available " + street(groupSats(onchain)) + ")") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fee, { fee = it.filter(Char::isDigit) }, label = { Text("Fee rate (sat/vB)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Explain("Minimum 20,000 sats. Leave some sats on-chain: closing and fee bumps need them. The channel is private: it is not announced and nobody routes other people's payments through your phone.")
        Spacer(Modifier.height(6.dp))
        Button(enabled = !busy && (amount.toLongOrNull() ?: 0) >= 20_000, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent), onClick = {
                busy = true; msg = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val (pk, host) = if (custom) node.substringBefore('@') to node.substringAfter('@', "")
                                             else Lightning.KILOMBINO_NODE to Lightning.KILOMBINO_HOST
                            require(pk.length == 66) { "The node must be pubkey@host:port" }
                            if (host.isNotEmpty()) Lnd.connect(pk, host)
                            Lnd.openChannel(pk, amount.toLong(), (fee.toLongOrNull() ?: 1).coerceAtLeast(1))
                        }
                    }.onSuccess { msg = "Opening. Funding transaction ${it.take(16)}…; usable once it confirms (the peer decides how many blocks, usually 1 to 3). Save a new backup file (Settings) so it includes this channel." to true; Lightning.refresh() }
                     .onFailure { msg = (it.message ?: "could not open the channel") to false }
                    busy = false
                }
            }) { Text(if (busy) "OPENING…" else "OPEN CHANNEL") }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ReceiveLn(accent: Color, ready: Boolean) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Panel(accent = accent) {
        SectionLabel("Invoice", accent)
        Spacer(Modifier.height(6.dp))
        if (!ready) Explain("You need an active channel, and can receive only up to what the other side holds in it (\"can receive\" under Channels). A new channel you opened starts with everything on your side.")
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Sats") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(memo, { memo = it }, label = { Text("Note (optional, the payer sees it)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = (amount.toLongOrNull() ?: 0) > 0, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent), onClick = {
                err = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Lnd.addInvoice(amount.toLong(), memo) } }
                        .onSuccess { invoice = it }.onFailure { err = it.message }
                }
            }) { Text("CREATE INVOICE") }
        invoice?.let { inv ->
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { QrImage(inv.uppercase(), 240) }
            Text(inv.take(40) + "…", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            TextButton(onClick = { clip.setText(AnnotatedString(inv)) }) { Text("COPY", color = accent) }
            Explain("Keep the app open until it is paid. Valid for one hour.")
        }
        err?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PayLn(accent: Color, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var req by remember { mutableStateOf<Lnd.PayReq?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Panel(accent = accent) {
        SectionLabel("Pay a Lightning invoice", accent)
        val clip = LocalClipboardManager.current
        OutlinedTextField(text, { text = it.trim(); req = null; msg = null }, label = { Text("Lightning invoice (lnbc…)") },
            modifier = Modifier.fillMaxWidth(), maxLines = 4)
        TextButton(onClick = { clip.getText()?.text?.trim()?.let { text = it; req = null; msg = null } }) { Text("PASTE", color = accent) }
        Button(enabled = text.length > 20 && !busy, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent), onClick = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Lnd.decode(text.removePrefix("lightning:")) } }
                        .onSuccess { req = it }.onFailure { msg = (it.message ?: "not a valid invoice") to false }
                }
            }) { Text("READ INVOICE") }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    req?.let { r ->
        val feeLimit = maxOf(10L, r.sats / 100)
        AlertDialog(onDismissRequest = { if (!busy) req = null },
            title = { Text("Pay ${groupSats(r.sats)} sats?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r.description.isNotBlank()) Text("“${r.description}”", style = MaterialTheme.typography.bodySmall)
                    Text("To ${r.destination.take(20)}…", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    Text("Routing fee at most ${groupSats(feeLimit)} sats.", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    if (r.sats == 0L) Text("This invoice has no amount: it is not supported yet.", color = Bad, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && r.sats > 0, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Lnd.pay(text.removePrefix("lightning:"), feeLimit) } }
                            .onSuccess { msg = "Paid." to true; text = ""; Lightning.refresh() }
                            .onFailure { msg = ("Not paid: " + (it.message ?: "failed")) to false }
                        busy = false; req = null
                    }
                }) { Text(if (busy) "PAYING…" else "PAY", color = accent) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { req = null }) { Text("CANCEL") } })
    }
}

/** Saves LND's channel backup on its own, for a restore without the full backup file. */
@Composable
private fun SaveChannelBackup(accent: Color) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val b = Lightning.channelBackupFile(ctx).readBytes()
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(b) }
        }.onSuccess { msg = "Channel backup saved. Save a new one after each channel you open or close." }
         .onFailure { msg = "Error: ${it.message}" }
    }
    Panel(accent = accent) {
        SectionLabel("Export channel backup (SCB)", accent)
        Spacer(Modifier.height(6.dp))
        Explain("The static channel backup: a small file, encrypted with the node's keys, that lists your channels and their peers. It does NOT keep the channels open. If this phone is lost and you have no up-to-date move file, your recovery words plus this file get the money back: the new node asks each peer to close the channel and the funds come back on-chain after their time lock. Save a new one after each channel you open or close; the backup file in Settings carries the latest one too.")
        TextButton(onClick = { save.launch("kilowallet-channel.backup") }) { Text("EXPORT SCB", color = accent) }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Error")) Bad else Good) }
    }
}

/**
 * Pays from the node's on-chain coins, with coin control: to empty the node once done with
 * Lightning (after closing the channels), or to move a coin out. Every coin is ticked to
 * start; with no amount, everything ticked goes (minus the fee).
 */
@Composable
private fun PayOnchain(accent: Color) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var coins by remember { mutableStateOf<List<Lnd.Utxo>?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var address by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("1") }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        coins = runCatching { withContext(Dispatchers.IO) { Lnd.utxos() } }.getOrDefault(emptyList())
        picked = coins.orEmpty().map { it.outpoint }.toSet()
    }
    val chosen = coins.orEmpty().filter { it.outpoint in picked }
    val total = chosen.sumOf { it.sats }
    val all = amount.isBlank()
    Panel(accent = accent) {
        SectionLabel("Pay on-chain from the node", accent)
        Spacer(Modifier.height(6.dp))
        Explain("To empty the node when you are done with Lightning (close the channels first), or to move a coin out. Tick the coins to spend; with no amount, all of them go.")
        when {
            coins == null -> Text("Loading the node's coins…", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            coins!!.isEmpty() -> Text("The node has no on-chain coins.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            else -> coins!!.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = u.outpoint in picked, onCheckedChange = { on ->
                        picked = if (on) picked + u.outpoint else picked - u.outpoint
                    })
                    Column {
                        Text(street(groupSats(u.sats)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
                        Text(u.outpoint.take(12) + "…:" + u.outpoint.substringAfter(':') + " · " +
                            (if (u.confirmations > 0) "${u.confirmations} conf" else "unconfirmed"),
                            style = MaterialTheme.typography.labelSmall, color = TextFaint)
                    }
                }
            }
        }
        OutlinedTextField(address, { address = it.trim() }, label = { Text("Address (bc1…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { clip.getText()?.text?.trim()?.removePrefix("bitcoin:")?.substringBefore('?')?.let { address = it } }) { Text("PASTE", color = accent) }
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Sats (blank = all ticked: " + street(groupSats(total)) + ")") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fee, { fee = it.filter(Char::isDigit) }, label = { Text("Fee rate (sat/vB)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Explain("With open anchor channels, LND keeps a reserve on-chain for fee bumping, so it may not let you send everything.")
        Button(enabled = !busy && chosen.isNotEmpty() && address.length > 20 && (all || (amount.toLongOrNull() ?: 0) > 0),
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = accent),
            onClick = { confirm = true }) { Text(if (busy) "SENDING…" else "SEND") }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (all) "Send everything ticked?" else "Send ${groupSats(amount.toLongOrNull() ?: 0)} sats?") },
        text = {
            Text("From ${chosen.size} coin(s) (${groupSats(total)} sats) of the Lightning node to ${address.take(14)}…${address.takeLast(6)} at " +
                "${fee.ifBlank { "1" }} sat/vB." + (if (all) " The fee comes out of the amount." else ""),
                style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = {
            TextButton(onClick = {
                confirm = false; busy = true; msg = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            Lnd.sendCoins(address.removePrefix("bitcoin:"), amount.toLongOrNull() ?: 0,
                                (fee.toLongOrNull() ?: 1).coerceAtLeast(1), all, chosen.map { it.outpoint })
                        }
                    }.onSuccess { msg = "Sent: transaction ${it.take(16)}…" to true; Lightning.refresh()
                        coins = runCatching { withContext(Dispatchers.IO) { Lnd.utxos() } }.getOrNull() }
                     .onFailure { msg = (it.message ?: "could not send") to false }
                    busy = false
                }
            }) { Text("SEND", color = accent) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("CANCEL") } })
}

@Composable
private fun PasswordFields(pw1: String, pw2: String?, on1: (String) -> Unit, on2: ((String) -> Unit)?) {
    OutlinedTextField(pw1, on1, label = { Text("password") }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    if (pw2 != null && on2 != null) OutlinedTextField(pw2, on2, label = { Text("repeat password") }, singleLine = true,
        isError = pw2.isNotEmpty() && pw1 != pw2,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
}

/**
 * Moves the node, channels open, to another phone: a password-protected file with its whole
 * state. Once the file is saved the node is deleted here, so it can never run twice.
 */
@Composable
private fun MoveOut(accent: Color) {
    val activity = LocalContext.current as FragmentActivity
    val ctx = activity.applicationContext
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var pack by remember { mutableStateOf<ByteArray?>(null) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val b = pack ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            // Not saved: the node stays here, stopped; it starts again with the app.
            pack = null; msg = "Not saved. Lightning stays on this phone (it starts again when you reopen the app)." to false
            return@rememberLauncherForActivityResult
        }
        runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(b) }
            val back = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            check(back.contentEquals(b)) { "the saved file does not match" }
        }.onSuccess { pack = null; LnMove.forgetHere(ctx) }
         .onFailure { msg = "Error saving: ${it.message}. Lightning stays on this phone." to false }
    }
    Panel(accent = accent) {
        SectionLabel("Move Lightning to another phone", accent)
        Spacer(Modifier.height(6.dp))
        Explain("Takes the node with its channels OPEN to a new phone: a file protected by a password, with the whole node. Once it is saved, Lightning is deleted from this phone. On the new phone, before turning Lightning on, choose MOVE IN FROM ANOTHER PHONE.")
        Text("Never use a copy of this file twice, and never turn Lightning on again here with these words while the other phone runs it: an old channel state is punished by losing the whole channel.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        TextButton(onClick = { ask = true; msg = null }) { Text("MOVE TO ANOTHER PHONE", color = accent) }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false },
        title = { Text("Move Lightning out of this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The node stops, its whole state goes into a file protected by this password, and Lightning is deleted here once the file is saved. Keep the file and the password: they are the only copy of the open channels until the other phone takes it in.",
                    style = MaterialTheme.typography.bodySmall)
                PasswordFields(pw1, pw2, { pw1 = it }, { pw2 = it })
            }
        },
        confirmButton = {
            TextButton(enabled = pw1.length >= 8 && pw1 == pw2, onClick = {
                val pw = pw1; ask = false; pw1 = ""; pw2 = ""
                Biometric.confirm(activity, "Move Lightning out", "Confirm it is you",
                    onSuccess = {
                        msg = "Stopping the node and packing it…" to true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { LnMove.export(ctx, pw) } }
                                .onSuccess { pack = it; msg = null; save.launch("kilowallet-lightning.move") }
                                .onFailure { msg = "Error: ${it.message}" to false }
                        }
                    },
                    onError = { msg = it to false })
            }) { Text("STOP AND PACK", color = Warn) }
        },
        dismissButton = { TextButton(onClick = { ask = false; pw1 = ""; pw2 = "" }) { Text("CANCEL") } })
}

/** Takes in a node moved from another phone ([MoveOut]), before Lightning is turned on here. */
@Composable
private fun MoveIn(accent: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var pw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { file = it; msg = null }.onFailure { msg = it.message }
    }
    Panel(accent = accent) {
        SectionLabel("Migrate to this phone (channels stay open)", accent)
        Spacer(Modifier.height(6.dp))
        Explain("If the old phone saved its node with MOVE TO ANOTHER PHONE, load that file here: the node starts with its channels open, as they were.")
        if (Lightning.movedAway(ctx)) Text("This phone's own node was moved out. Turn Lightning on here again only if the other phone no longer runs it.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        if (Lnd.stoppedThisRun) Text("Close the app with ⏻ and open it again first: the old node has to let go of its files.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        else if (file == null) TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("MOVE IN FROM ANOTHER PHONE", color = accent) }
        else {
            PasswordFields(pw, null, { pw = it }, null)
            TextButton(enabled = pw.isNotEmpty(), onClick = {
                val b = file!!; val p = pw
                msg = "Unpacking…"
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { LnMove.import(ctx, b, p) } }
                        .onSuccess { file = null; pw = ""; msg = null; Lightning.resume(ctx) }
                        .onFailure { msg = it.message }
                }
            }) { Text("OPEN AND START", color = accent) }
        }
        msg?.let { Text(it, color = if (it == "Unpacking…") TextSoft else Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

/** With the node running: hand it a channel backup, so the channels in it get closed and paid back. */
@Composable
private fun RestoreChannelsLive(accent: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { file = it }.onFailure { msg = (it.message ?: "could not read the file") to false }
    }
    Panel(accent = accent) {
        SectionLabel("Restore channels from an SCB", accent)
        Spacer(Modifier.height(6.dp))
        Explain("For channels of an old phone that is gone (same recovery words): this node asks each of their peers to close them, and the money comes back on-chain after the time lock.")
        Text("Every channel in that file gets closed. Only if the old phone no longer runs Lightning.", style = MaterialTheme.typography.bodySmall, color = Warn)
        TextButton(onClick = { msg = null; open.launch(arrayOf("*/*")) }) { Text("LOAD SCB AND CLOSE ITS CHANNELS", color = accent) }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    file?.let { b ->
        AlertDialog(onDismissRequest = { file = null },
            title = { Text("Close the channels in this backup?") },
            text = { Text("The node asks each peer to close them and the money comes back on-chain. This cannot be undone.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    file = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Lnd.restoreChannelBackups(b) } }
                            .onSuccess { msg = "Done: the peers are being asked to close. The money shows on-chain in the node after their time lock." to true; Lightning.refresh() }
                            .onFailure { msg = (it.message ?: "the node refused the backup") to false }
                    }
                }) { Text("CLOSE THEM", color = Warn) }
            },
            dismissButton = { TextButton(onClick = { file = null }) { Text("CANCEL") } })
    }
}

/**
 * An empty node (no channels, nothing on-chain) can be deleted, so another phone's node can
 * be moved in here. One that holds money cannot: close the channels and send the coins out first.
 */
@Composable
private fun DeleteEmptyNode(accent: Color, s: Lightning.Status.Running) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    val b = s.balances
    val empty = s.channels.isEmpty() && s.pending.isEmpty() && b != null &&
        b.onchainConfirmed == 0L && b.onchainUnconfirmed == 0L && b.channels == 0L && b.pendingOpen == 0L
    Panel(accent = accent) {
        SectionLabel("Delete this node", accent)
        Spacer(Modifier.height(6.dp))
        Explain(if (empty) "This node holds nothing. Delete it to move another phone's node in here (MIGRATE TO THIS PHONE, then shown with Lightning off)."
                else "Only an empty node can be deleted: close its channels and send its on-chain coins out (PAY → on-chain) first.")
        TextButton(enabled = empty, onClick = { ask = true }) { Text("DELETE THIS NODE", color = if (empty) Warn else TextFaint) }
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false },
        title = { Text("Delete this Lightning node?") },
        text = { Text("It holds no money. Turning Lightning on again later makes a new node from your words.", style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = {
                ask = false
                scope.launch { withContext(Dispatchers.IO) { Lightning.deleteNode(ctx) } }
            }) { Text("DELETE", color = Warn) }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("CANCEL") } })
}
