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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The LN tab (advanced, hot wallet): a Lightning node on the phone, LND with the neutrino light
 * client, self-custodial. Off until the user turns it on; beta, and it says so.
 */
@Composable
fun LightningScreen(vm: WalletViewModel, accent: Color) {
    val activity = LocalContext.current as FragmentActivity
    val ctx = activity.applicationContext
    val ui by vm.state.collectAsState()
    val status by Lightning.status.collectAsState()
    Lightning.chainTip = ui.current.height

    LaunchedEffect(Unit) { Lightning.resume(ctx) }
    DisposableEffect(Unit) { Lightning.watch(true); onDispose { Lightning.watch(false) } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = status) {
            is Lightning.Status.Off -> LnIntro(accent) { onError ->
                turnOn(activity, vm, onError)
            }
            is Lightning.Status.Starting -> Panel(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(accent); Spacer(Modifier.width(8.dp))
                    Text(s.step.replaceFirstChar { it.uppercase() } + "…", color = TextSoft)
                }
                Spacer(Modifier.height(6.dp))
                Explain("The first start downloads block headers and filters from the peers (a few hundred MB, a few minutes on Wi-Fi). Later starts only catch up.")
            }
            is Lightning.Status.Failed -> Panel(accent = Bad) {
                Text("Lightning did not start", color = Bad, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = TextSoft)
                TextButton(onClick = { Lightning.resume(ctx) }) { Text("TRY AGAIN", color = accent) }
            }
            is Lightning.Status.Running -> LnNode(s, accent)
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
        Button(onClick = { error = null; onTurnOn { error = it } }, enabled = agreed, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("TURN ON LIGHTNING") }
        error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

private enum class LnSheet { None, Fund, Open, Receive, Pay }

@Composable
private fun LnNode(s: Lightning.Status.Running, accent: Color) {
    var sheet by remember { mutableStateOf(LnSheet.None) }
    val i = s.info
    val b = s.balances
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(if (i.syncedChain) Good else Warn); Spacer(Modifier.width(8.dp))
            val sync = if (i.syncedChain) "Synced" else "Syncing"
            val h = if (s.tip > 0 && !i.syncedChain) "block ${groupSats(i.height.toLong())} of ${groupSats(s.tip.toLong())}" else "block ${groupSats(i.height.toLong())}"
            Text("$sync · $h · ${i.peers} peer" + (if (i.peers == 1) "" else "s"), style = MaterialTheme.typography.bodySmall, color = TextSoft)
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
        listOf("FUND" to LnSheet.Fund, "CHANNEL" to LnSheet.Open, "RECEIVE" to LnSheet.Receive, "PAY" to LnSheet.Pay).forEach { (t, v) ->
            Button(onClick = { sheet = if (sheet == v) LnSheet.None else v }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (sheet == v) accent else accent.copy(alpha = 0.25f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                Text(t, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = if (sheet == v) Ink else TextMain)
            }
        }
    }

    when (sheet) {
        LnSheet.Fund -> FundSheet(accent)
        LnSheet.Open -> OpenSheet(accent, b?.onchainConfirmed ?: 0) { sheet = LnSheet.None }
        LnSheet.Receive -> ReceiveLn(accent, i.syncedChain && s.channels.any { it.active })
        LnSheet.Pay -> PayLn(accent) { sheet = LnSheet.None }
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
    var custom by remember { mutableStateOf(false) }
    var node by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("2") }
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
                            Lnd.openChannel(pk, amount.toLong(), (fee.toLongOrNull() ?: 2).coerceAtLeast(1))
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
        SectionLabel("Receive", accent)
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
        SectionLabel("Pay", accent)
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
