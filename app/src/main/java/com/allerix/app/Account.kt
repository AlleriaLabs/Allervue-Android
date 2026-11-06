package com.allerix.app

import android.content.Context
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ login
@Composable
fun LoginScreen(onDone: () -> Unit, onSettings: () -> Unit, onForgot: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = ctx as? FragmentActivity
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var setupBiometric by remember { mutableStateOf(false) }
    var pendingCredentials by remember { mutableStateOf<Pair<String, String>?>(null) }
    val biometricAvailable = remember(ctx) {
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    val hasBiometricLogin = remember(ctx) { BiometricLogin.hasSavedCredentials(ctx) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Red, unfocusedBorderColor = Color(0xFF555555),
        cursorColor = Red, focusedLabelColor = Red, unfocusedLabelColor = Grey,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White
    )

    fun showBiometricPrompt(encrypt: Boolean) {
        val host = activity
        if (host == null) { err = "Biometric sign-in isn't available in this screen."; return }
        try {
            val cipher = if (encrypt) BiometricLogin.newEncryptCipher() else BiometricLogin.newDecryptCipher(ctx)
            val prompt = BiometricPrompt(
                host, host.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val unlockedCipher = result.cryptoObject?.cipher
                        if (unlockedCipher == null) {
                            err = "Secure biometric authentication failed. Use your password."
                            if (encrypt) onDone()
                            return
                        }
                        if (encrypt) {
                            val credentials = pendingCredentials
                            if (credentials != null) {
                                runCatching {
                                    BiometricLogin.save(ctx, unlockedCipher, credentials.first, credentials.second)
                                }.onSuccess { onDone() }
                                    .onFailure { err = "Couldn't enable biometric sign-in."; onDone() }
                            } else onDone()
                        } else {
                            try {
                                val credentials = BiometricLogin.decrypt(ctx, unlockedCipher)
                                scope.launch {
                                    try { Api.login(ctx, credentials.username, credentials.password); onDone() }
                                    catch (e: Exception) { BiometricLogin.clear(ctx); err = "Saved biometric login is no longer valid. Sign in with your password and enable it again." }
                                }
                            } catch (e: Exception) {
                                BiometricLogin.clear(ctx)
                                err = "Biometric login needs to be set up again. Sign in with your password."
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        err = if (encrypt) "Biometric setup cancelled. You can enable it later." else
                            "Biometric sign-in cancelled. Use your password instead."
                        if (encrypt) onDone()
                    }

                    override fun onAuthenticationFailed() {
                        err = "Biometric not recognized. Try again."
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (encrypt) "Enable Allervue biometric sign-in" else "Sign in to Allervue")
                .setSubtitle("Use your enrolled fingerprint or supported face authentication")
                .setNegativeButtonText("Use password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            err = "Biometric sign-in isn't available. Use your password."
            if (encrypt) onDone()
        }
    }

    AppBackground {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.ic_allervue_emblem), "Allervue emblem", Modifier.size(72.dp))
                Spacer(Modifier.width(12.dp))
                Text("ALLERVUE", color = Color(0xFFD8B77E), fontSize = 25.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text("Sign In", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true,
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), colors = fieldColors,
                modifier = Modifier.fillMaxWidth())
            if (biometricAvailable && hasBiometricLogin) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    enabled = !busy, onClick = { err = ""; showBiometricPrompt(false) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Red)
                ) {
                    Text("◎  Sign in with fingerprint / face", color = Red, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                onClick = {
                    busy = true; err = ""
                    scope.launch {
                        try {
                            val name = user.trim()
                            Api.login(ctx, name, pw)
                            if (biometricAvailable && activity != null) {
                                pendingCredentials = name to pw
                                setupBiometric = true
                            } else onDone()
                        } catch (e: Exception) {
                            err = "Couldn't sign in. Check your username and password."
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Signing in…" else "Sign In", fontWeight = FontWeight.Bold) }
            TextButton(onClick = onForgot) { Text("Forgot password?", color = Grey) }
            if (err.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(err, color = Color(0xFFFF6B6B))
            }
        }
        TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Text("⚙", fontSize = 24.sp, color = Grey)
        }
        Text(
            "DISCLAIMER: Allervue is a private service for authorized members only. " +
                "Content is provided for personal, non-commercial viewing. Copying, recording, " +
                "sharing your account or redistributing any content is prohibited. " +
                "By signing in you accept these terms.",
            color = Color(0xFF8C8C8C), fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 20.dp)
        )
    }

    if (setupBiometric) {
        AlertDialog(
            onDismissRequest = { setupBiometric = false; onDone() },
            containerColor = Color(0xFF181818),
            title = { Text("Enable biometric sign-in?", color = Color.White) },
            text = {
                Text(
                    "Save this account securely on this device so you can sign in next time with an enrolled fingerprint or supported face unlock. Your password is encrypted with Android Keystore and requires biometric authentication to decrypt.",
                    color = Grey
                )
            },
            confirmButton = {
                TextButton(onClick = { setupBiometric = false; showBiometricPrompt(true) }) {
                    Text("Enable", color = Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { setupBiometric = false; onDone() }) {
                    Text("Not now", color = Grey)
                }
            }
        )
    }
}


@Composable
fun ForgotPasswordScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var stage by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var success by remember { mutableStateOf(false) }
    val colors = appFieldColors()

    AppBackground {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(painterResource(R.drawable.ic_allervue_emblem), "Allervue emblem", Modifier.size(76.dp))
            Spacer(Modifier.height(12.dp))
            Text("ALLERVUE", color = Color(0xFFD8B77E), fontSize = 22.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(30.dp))
            Text(if (success) "Password updated" else "Reset password", color = Color.White,
                fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    success -> "Your password has been changed. You can now return to sign in."
                    stage == 0 -> "Enter your Allervue username to request a password reset."
                    else -> "Enter the reset PIN sent through your account recovery process, then choose a new password."
                },
                color = Grey, fontSize = 14.sp, modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(22.dp))
            if (success) {
                Text("✓", color = Color(0xFFD8B77E), fontSize = 48.sp)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Back to sign in") }
            } else if (stage == 0) {
                OutlinedTextField(username, { username = it; message = "" }, label = { Text("Username or email") },
                    singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !busy && username.isNotBlank(), onClick = {
                        busy = true; message = ""
                        scope.launch {
                            try {
                                Api.forgotPassword(username.trim())
                                stage = 1
                                message = "If the account is eligible, follow the reset instructions sent to your registered contact method."
                            } catch (e: Exception) {
                                message = "Couldn't start password recovery. Check the username and try again."
                            }
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) { Text(if (busy) "Requesting…" else "Send reset instructions", fontWeight = FontWeight.Bold) }
            } else {
                OutlinedTextField(pin, { pin = it; message = "" }, label = { Text("Reset PIN") },
                    singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(password, { password = it; message = "" }, label = { Text("New password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(confirm, { confirm = it; message = "" }, label = { Text("Confirm new password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !busy && pin.isNotBlank() && password.isNotBlank() && confirm.isNotBlank(),
                    onClick = {
                        when {
                            password.length < 8 -> message = "Choose a longer password (at least 8 characters)."
                            password != confirm -> message = "The passwords don't match."
                            else -> {
                                busy = true; message = ""
                                scope.launch {
                                    try {
                                        Api.setPasswordWithResetPin(pin, password)
                                        success = true
                                    } catch (e: Exception) {
                                        message = e.message ?: "Password reset failed. Please try again."
                                    }
                                    busy = false
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) { Text(if (busy) "Updating…" else "Update password", fontWeight = FontWeight.Bold) }
                TextButton(onClick = {
                    stage = 0; pin = ""; password = ""; confirm = ""; message = ""
                }) { Text("Request a new PIN", color = Grey) }
            }
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(message, color = if (success) Color(0xFFD8B77E) else Grey, fontSize = 13.sp)
            }
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onBack) { Text("Back to sign in", color = Grey) }
        }
    }
    BackHandler { onBack() }
}

// ------------------------------------------------------------------ settings
@Composable
private fun appFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Red, unfocusedBorderColor = Color(0xFF555555),
    cursorColor = Red, focusedLabelColor = Red, unfocusedLabelColor = Grey,
    focusedTextColor = Color.White, unfocusedTextColor = Color.White
)

@Composable
private fun ScreenHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = back) { Text("‹", color = Color.White, fontSize = 28.sp) }
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Grey, fontSize = 13.sp)
        }
        Text("›", color = Grey, fontSize = 22.sp)
    }
    HorizontalDivider(color = Color(0xFF222222))
}

@Composable
fun SettingsMenuScreen(
    back: () -> Unit, onRequest: () -> Unit, onClient: () -> Unit, onPlayback: () -> Unit,
    onSignOut: () -> Unit, onWeb: (String, String) -> Unit
) {
    var help by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ScreenHeader("Settings", back)
        SettingsRow("🎬", "Request a show", "Ask for a movie, series or anime to be added", onRequest)
        SettingsRow("▶", "Playback settings", "Quality, audio, speed, auto-play", onPlayback)
        SettingsRow("⚙", "Client settings", "Server address and password", onClient)
        SettingsRow("?", "Help & Support", "Account portal and password reset") { help = true }
        SettingsRow("⎋", "Sign out", "", onSignOut)
        Spacer(Modifier.weight(1f))
        Text("Allervue 1.0", color = Color(0xFF666666), fontSize = 12.sp,
            modifier = Modifier.padding(20.dp))
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false }, containerColor = Color(0xFF181818),
        title = { Text("Help & Support", color = Color.White) },
        text = {
            Text(
                "Forgot your password or need to update your email, Telegram or Discord? Use the account portal. " +
                    "To ask for a movie or show, choose Request a show in this menu.",
                color = Grey, fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = { help = false; onWeb(Api.ACCOUNT_URL, "My account") }) {
                Text("Open account portal", color = Red)
            }
        },
        dismissButton = { TextButton(onClick = { help = false }) { Text("Close", color = Grey) } }
    )
}

@Composable
fun ClientSettingsScreen(
    back: () -> Unit, onServerChanged: () -> Unit, loggedIn: Boolean,
    onWeb: (String, String) -> Unit = { _, _ -> }
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Api.base) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var cur by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var pwStatus by remember { mutableStateOf("") }
    var pwBusy by remember { mutableStateOf(false) }
    val colors = appFieldColors()

    Column(Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
        ScreenHeader("Client settings", back)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("SERVER", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                url, { url = it; status = "" }, label = { Text("Server URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    enabled = !busy, shape = RoundedCornerShape(4.dp),
                    onClick = {
                        busy = true; status = "Testing…"
                        scope.launch {
                            status = try { "✓ Connected: ${Api.ping(url)}" }
                            catch (e: Exception) { "✗ Can't connect (${e.message})" }
                            busy = false
                        }
                    }
                ) { Text("Test connection", color = Color.White) }
                Button(
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                    onClick = {
                        val n = Api.normalize(url)
                        if (n.isEmpty()) status = "Enter a server URL"
                        else {
                            val changed = n != Api.base
                            Api.setServer(ctx, n); url = n
                            if (changed) { Api.logout(ctx); onServerChanged() }
                            else status = "Saved"
                        }
                    }
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
            if (status.isNotEmpty()) Text(status, color = Grey, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { url = Api.SERVER; status = "" }) { Text("Reset to default", color = Grey) }
            Text("Changing the server signs you out so you can log in to the new one.",
                color = Color(0xFF777777), fontSize = 12.sp)

            if (loggedIn) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(Modifier.height(16.dp))
                Text("CHANGE PASSWORD", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(cur, { cur = it }, label = { Text("Current password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(new1, { new1 = it }, label = { Text("New password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(new2, { new2 = it }, label = { Text("Confirm new password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = !pwBusy, shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                    onClick = {
                        when {
                            new1.isEmpty() -> pwStatus = "Enter a new password"
                            new1 != new2 -> pwStatus = "New passwords don't match"
                            else -> {
                                pwBusy = true; pwStatus = "Changing…"
                                scope.launch {
                                    pwStatus = try {
                                        Api.changePassword(cur, new1)
                                        cur = ""; new1 = ""; new2 = ""
                                        "✓ Password changed"
                                    } catch (e: Exception) {
                                        if (e.message?.contains("40") == true) "Current password is incorrect"
                                        else "Couldn't change password (${e.message})"
                                    }
                                    pwBusy = false
                                }
                            }
                        }
                    }
                ) { Text("Change password", fontWeight = FontWeight.Bold) }
                if (pwStatus.isNotEmpty()) Text(pwStatus, color = Grey, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }

            if (loggedIn && Api.JFA_URL.isNotEmpty()) {
                TextButton(onClick = { onWeb(Api.ACCOUNT_URL, "My account") }) { Text("Manage account on the web (email, Telegram, Discord)", color = Red) }
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFF333333))
            Spacer(Modifier.height(16.dp))
            Text("PLAYER", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Set default video quality and audio output in Settings, Playback settings. " +
                "You can also change them from the More menu inside the player.",
                color = Grey, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun RequestShowScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Movie") }
    var status by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val colors = appFieldColors()

    Column(Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
        ScreenHeader("Request a show", back)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                title, { title = it; status = "" }, label = { Text("Title *") }, singleLine = true,
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                year, { if (it.length <= 4 && it.all { c -> c.isDigit() }) { year = it; status = "" } },
                label = { Text("Year (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = colors, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Text("CATEGORY", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Movie", "Series", "Anime").forEach { c ->
                    Button(
                        onClick = { category = c }, shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (c == category) Red else Color(0xFF2A2A2A),
                            contentColor = Color.White
                        )
                    ) { Text(c) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                onClick = {
                    val t = title.trim()
                    val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                    when {
                        t.isEmpty() -> { status = "Title is required"; isError = true }
                        year.isNotEmpty() && (year.length != 4 || year.toInt() !in 1888..(thisYear + 3)) ->
                            { status = "Enter a valid 4-digit year, or leave it empty"; isError = true }
                        else -> {
                            busy = true; isError = false; status = "Sending…"
                            scope.launch {
                                try {
                                    if (Api.sendRequest(t, year, category)) {
                                        status = "✓ Request sent. Thank you!"
                                        title = ""; year = ""
                                    } else {
                                        ctx.startActivity(Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "Show request: $t")
                                                putExtra(Intent.EXTRA_TEXT, Api.requestText(t, year, category))
                                            }, "Send request via"))
                                        status = "Choose an app to send your request to the admin."
                                    }
                                } catch (e: Exception) {
                                    isError = true; status = "Couldn't send request (${e.message})"
                                }
                                busy = false
                            }
                        }
                    }
                }
            ) { Text("Submit request", fontWeight = FontWeight.Bold) }
            if (status.isNotEmpty()) Text(status, color = if (isError) Color(0xFFFF6B6B) else Grey,
                fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// ------------------------------------------------------------------ playback settings
fun speedLabel(f: Float): String = (if (f % 1f == 0f) f.toInt().toString() else f.toString()) + "x"

@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181818),
        title = { Text(title, color = Color.White) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = value == selected, onClick = { onPick(value); onDismiss() },
                            colors = RadioButtonDefaults.colors(selectedColor = Red, unselectedColor = Grey)
                        )
                        Text(label, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Grey) } }
    )
}

@Composable
fun PlaybackSettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("player", Context.MODE_PRIVATE) }
    var quality by remember {
        mutableStateOf(runCatching { Quality.valueOf(prefs.getString("quality", "AUTO") ?: "AUTO") }.getOrDefault(Quality.AUTO))
    }
    var audio by remember {
        mutableStateOf(runCatching { AudioMode.valueOf(prefs.getString("audio", "ORIGINAL") ?: "ORIGINAL") }.getOrDefault(AudioMode.ORIGINAL))
    }
    var decoder by remember {
        mutableStateOf(runCatching { DecoderMode.valueOf(prefs.getString("decoder", "HW") ?: "HW") }.getOrDefault(DecoderMode.HW))
    }
    var speed by remember { mutableStateOf(prefs.getFloat("speed", 1f)) }
    var autoPlay by remember { mutableStateOf(prefs.getBoolean("autoplay", true)) }
    var saveData by remember { mutableStateOf(prefs.getBoolean("save_data", true)) }
    var dialog by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ScreenHeader("Playback Settings", back)
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(18.dp)) {
            SettingsRow("HD", "Quality", quality.label) { dialog = 1 }
            SettingsRow("🔊", "Audio output", audio.label) { dialog = 2 }
            SettingsRow("⏱", "Playback speed", speedLabel(speed)) { dialog = 3 }
            SettingsRow("⚙", "Decoder", "${decoder.label} · ${decoder.desc}") { dialog = 4 }
            SettingsRow("📱", "This phone", "Decoders, HDR, audio output, network") { dialog = 5 }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Save data on mobile networks", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Auto mode lowers quality on mobile data", color = Grey, fontSize = 13.sp)
                }
                Switch(
                    checked = saveData,
                    onCheckedChange = { saveData = it; prefs.edit().putBoolean("save_data", it).apply() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Red,
                        uncheckedThumbColor = Grey, uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto Play", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Next episode", color = Grey, fontSize = 13.sp)
                }
                Switch(
                    checked = autoPlay,
                    onCheckedChange = { autoPlay = it; prefs.edit().putBoolean("autoplay", it).apply() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Red,
                        uncheckedThumbColor = Grey, uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }
        }
        Text(
            "Auto checks what this phone can decode and only transcodes when needed. Other qualities always use server transcoding.",
            color = Dim, fontSize = 12.sp, modifier = Modifier.padding(20.dp)
        )
    }

    when (dialog) {
        1 -> ChoiceDialog("Quality", Quality.values().map { it.label to it }, quality,
            { quality = it; prefs.edit().putString("quality", it.name).apply() }, { dialog = 0 })
        2 -> ChoiceDialog("Audio output", AudioMode.values().map { it.label to it }, audio,
            { audio = it; prefs.edit().putString("audio", it.name).apply() }, { dialog = 0 })
        3 -> ChoiceDialog("Playback speed", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { speedLabel(it) to it }, speed,
            { speed = it; prefs.edit().putFloat("speed", it).apply() }, { dialog = 0 })
        4 -> ChoiceDialog("Decoder", DecoderMode.values().map { "${it.label} · ${it.desc}" to it }, decoder,
            { decoder = it; prefs.edit().putString("decoder", it.name).apply() }, { dialog = 0 })
        5 -> AlertDialog(
            onDismissRequest = { dialog = 0 }, containerColor = Color(0xFF181818),
            title = { Text("This phone", color = Color.White) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Device.describe(ctx, null).forEach { (k, v) ->
                        Text(k, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                        Text(v, color = Color.White, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = 0 }) { Text("Close", color = Grey) } }
        )
        else -> {}
    }
}

// ------------------------------------------------------------------ in-app web page
@Composable
fun WebScreen(url: String, title: String, back: () -> Unit) {
    val holder = remember { arrayOfNulls<WebView>(1) }
    var progress by remember { mutableStateOf(0) }
    BackHandler {
        val w = holder[0]
        if (w != null && w.canGoBack()) w.goBack() else back()
    }
    DisposableEffect(Unit) { onDispose { holder[0]?.destroy() } }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ScreenHeader(title, back)
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Red, trackColor = Color.Transparent
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setBackgroundColor(android.graphics.Color.BLACK)
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                    }
                    holder[0] = this
                    loadUrl(url)
                }
            }
        )
    }
}
