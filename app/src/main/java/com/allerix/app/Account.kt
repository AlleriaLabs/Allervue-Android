package com.allerix.app

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
    val biometricAvailable = remember(ctx) {
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    val hasBiometricLogin = remember(ctx) { BiometricLogin.hasSavedCredentials(ctx) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Red, unfocusedBorderColor = Color(0xFF555555),
        cursorColor = Red, focusedLabelColor = Red, unfocusedLabelColor = Grey,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedContainerColor = Color(0xFF201D30),
        unfocusedContainerColor = Color(0xFF1A1828)
    )

    fun showBiometricPrompt() {
        val host = activity
        if (host == null) { err = "Biometric sign-in isn't available in this screen."; return }
        try {
            val cipher = BiometricLogin.newDecryptCipher(ctx)
            val prompt = BiometricPrompt(
                host, host.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val unlockedCipher = result.cryptoObject?.cipher
                        if (unlockedCipher == null) {
                            err = "Secure biometric authentication failed. Use your password."
                            return
                        }
                        try {
                            val credentials = BiometricLogin.decrypt(ctx, unlockedCipher)
                            scope.launch {
                                try {
                                    Api.login(ctx, credentials.username, credentials.password)
                                    onDone()
                                } catch (e: Exception) {
                                    BiometricLogin.clear(ctx)
                                    err = "Saved biometric login is no longer valid. Sign in with your password and enable it again."
                                }
                            }
                        } catch (e: Exception) {
                            BiometricLogin.clear(ctx)
                            err = "Biometric login needs to be set up again. Sign in with your password."
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        err = "Biometric sign-in cancelled. Use your password instead."
                    }

                    override fun onAuthenticationFailed() {
                        err = "Biometric not recognized. Try again."
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Sign in to Allervue")
                .setSubtitle("Use your enrolled fingerprint or supported face authentication")
                .setNegativeButtonText("Use password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            err = "Biometric sign-in isn't available. Use your password."
        }
    }

    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.verticalGradient(
                listOf(Color(0xFF32191F), Color(0xFF17152D), Color(0xFF071F27))
            ))
            drawCircle(
                Brush.radialGradient(listOf(Color(0x66FF665D), Color.Transparent)),
                radius = size.minDimension * .72f,
                center = androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .12f)
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0x559B46E8), Color.Transparent)),
                radius = size.minDimension * .78f,
                center = androidx.compose.ui.geometry.Offset(size.width * .94f, size.height * .32f)
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0x3328D5D0), Color.Transparent)),
                radius = size.minDimension * .62f,
                center = androidx.compose.ui.geometry.Offset(size.width * .38f, size.height * .98f)
            )
        }
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(top = 54.dp, bottom = 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AllervueWordmark(
                emblemSize = 64.dp,
                textSize = 38.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "SEE IT ALL",
                color = Color(0xFFB9B4C8),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(34.dp))

            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xCC141323))
                    .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 22.dp, vertical = 26.dp)
            ) {
                Text("Welcome back", fontSize = 27.sp, fontWeight = FontWeight.Bold,
                    color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text("Sign in to continue watching", fontSize = 14.sp,
                    color = Color(0xFFAAA8B8))
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    user, { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    pw, { pw = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(15.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                if (biometricAvailable && hasBiometricLogin) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        enabled = !busy, onClick = { err = ""; showBiometricPrompt() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6682))
                    ) {
                        Text("Use fingerprint / face", color = Color(0xFFFF8298),
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF557D),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF75405A)
                    ),
                    onClick = {
                        busy = true; err = ""
                        scope.launch {
                            try {
                                val name = user.trim()
                                Api.login(ctx, name, pw)
                                onDone()
                            } catch (e: Exception) {
                                err = "Couldn't sign in. Check your username and password."
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(if (busy) "Signing in…" else "Sign In",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                TextButton(
                    onClick = onForgot,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Forgot password?", color = Color(0xFFB9B4C8))
                }
                if (err.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(err, color = Color(0xFFFF8298), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "PRIVATE ACCESS  •  AUTHORIZED MEMBERS ONLY",
                color = Color(0xFFAAA6B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "By signing in you accept the terms. Personal, non-commercial viewing only. " +
                    "Copying, recording, sharing accounts or redistributing content is prohibited.",
                color = Color(0xFF858294), fontSize = 11.sp, lineHeight = 15.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        TextButton(
            onClick = onSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0x331C1930))
        ) {
            Text("⚙", fontSize = 22.sp, color = Color(0xFFD5D0E2))
        }
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
    Column(Modifier.fillMaxWidth().background(BrandInk)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                    .background(BrandSurface).border(1.dp, BrandGold.copy(alpha = .22f), RoundedCornerShape(14.dp))
                    .clickable(onClick = back),
                contentAlignment = Alignment.Center
            ) { Text("‹", color = BrandGoldBright, fontSize = 30.sp) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = BrandIvory, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("ALLERVUE SETTINGS", color = BrandGold.copy(alpha = .82f),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            }
        }
        HorizontalDivider(color = BrandGold.copy(alpha = .14f))
    }
}

@Composable
private fun SettingsRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(17.dp)).background(BrandSurface)
            .border(1.dp, BrandGold.copy(alpha = .12f), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(BrandGold.copy(alpha = .12f)),
            contentAlignment = Alignment.Center
        ) { Text(icon, color = BrandGoldBright, fontSize = 19.sp) }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(title, color = BrandIvory, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Grey, fontSize = 12.sp)
        }
        Text("›", color = BrandGold, fontSize = 25.sp)
    }
}

@Composable
fun SettingsMenuScreen(
    back: () -> Unit, onRequest: () -> Unit, onClient: () -> Unit, onPlayback: () -> Unit,
    onSignOut: () -> Unit, onWeb: (String, String) -> Unit, onDiagnostics: () -> Unit,
    onProfile: () -> Unit, onAppearance: () -> Unit
) {
    var help by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "Unknown"
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", back)
        SettingsRow("◉", "Profile & account", Api.username.ifBlank { "Manage your Jellyfin account" }, onProfile)
        SettingsRow("✦", "Appearance", "Theme and display preferences", onAppearance)
        SettingsRow("🎬", "Request a show", "Ask for a movie, series or anime to be added", onRequest)
        SettingsRow("▶", "Playback settings", "Quality, audio, speed, auto-play", onPlayback)
        SettingsRow("⚙", "Client settings", "Server address and password", onClient)
        SettingsRow("⌁", "Diagnostics", "Capture player crashes and share a log", onDiagnostics)
        SettingsRow("ⓘ", "About Allervue", "App information and version") { about = true }
        SettingsRow("?", "Help & Support", "Account portal and password reset") { help = true }
        SettingsRow("⎋", "Sign out", "", onSignOut)
        Spacer(Modifier.weight(1f))
        Text("Allervue $appVersion", color = Color(0xFF666666), fontSize = 12.sp,
            modifier = Modifier.padding(20.dp))
    }
    if (about) AlertDialog(
        onDismissRequest = { about = false }, containerColor = Color(0xFF181818),
        title = { Text("About Allervue", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ALLERVUE", color = Color(0xFFD8B77E), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Version $appVersion", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Allervue is an Alleria Labs media experience for browsing your Jellyfin library, " +
                        "watching movies and series, managing downloads, and requesting titles through Allervue.",
                    color = Grey, fontSize = 14.sp
                )
                Text("Great stories. Anytime. Anywhere.", color = Color(0xFFD8B77E), fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { about = false }) { Text("Close", color = Red) } }
    )
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
    var bioUser by remember { mutableStateOf("") }
    var bioPassword by remember { mutableStateOf("") }
    var bioStatus by remember { mutableStateOf("") }
    var bioBusy by remember { mutableStateOf(false) }
    val activity = ctx as? FragmentActivity
    val biometricAvailable = remember(ctx) {
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    var biometricEnabled by remember { mutableStateOf(BiometricLogin.hasSavedCredentials(ctx)) }
    val colors = appFieldColors()

    fun enableBiometricLogin() {
        val host = activity
        if (host == null) {
            bioStatus = "Biometric sign-in isn't available on this screen."
            return
        }
        if (bioUser.isBlank() || bioPassword.isBlank()) {
            bioStatus = "Enter your Jellyfin username and password first."
            return
        }
        try {
            val cipher = BiometricLogin.newEncryptCipher()
            val prompt = BiometricPrompt(
                host, host.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val unlockedCipher = result.cryptoObject?.cipher
                        if (unlockedCipher == null) {
                            bioStatus = "Secure biometric setup failed."
                            return
                        }
                        runCatching {
                            BiometricLogin.save(ctx, unlockedCipher, bioUser.trim(), bioPassword)
                        }.onSuccess {
                            biometricEnabled = true
                            bioPassword = ""
                            bioStatus = "Biometric sign-in enabled on this device."
                        }.onFailure {
                            bioStatus = "Couldn't enable biometric sign-in."
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        bioStatus = "Biometric setup cancelled. You can try again anytime."
                    }

                    override fun onAuthenticationFailed() {
                        bioStatus = "Biometric not recognized. Try again."
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable Allervue biometric sign-in")
                .setSubtitle("Use your enrolled fingerprint or supported face authentication")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            bioStatus = "Biometric sign-in isn't available. Use your password."
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

            if (loggedIn) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(Modifier.height(16.dp))
                Text("BIOMETRIC SIGN-IN", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (biometricEnabled) "Biometric sign-in is enabled on this device."
                    else "Optional. Enable fingerprint or supported face authentication for quicker sign-in next time.",
                    color = Grey, fontSize = 13.sp
                )
                if (biometricEnabled) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            BiometricLogin.clear(ctx)
                            biometricEnabled = false
                            bioPassword = ""
                            bioStatus = "Biometric sign-in disabled on this device."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disable biometric sign-in", color = Color.White) }
                } else {
                    if (!biometricAvailable) {
                        Text("No supported strong biometric is enrolled on this device.",
                            color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            bioUser, { bioUser = it; bioStatus = "" },
                            label = { Text("Jellyfin username") }, singleLine = true,
                            colors = colors, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            bioPassword, { bioPassword = it; bioStatus = "" },
                            label = { Text("Jellyfin password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = colors, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            enabled = !bioBusy && bioUser.isNotBlank() && bioPassword.isNotBlank(),
                            onClick = {
                                bioBusy = true
                                bioStatus = "Verifying account…"
                                scope.launch {
                                    try {
                                        Api.login(ctx, bioUser.trim(), bioPassword)
                                        bioStatus = "Confirm with your fingerprint or face."
                                        enableBiometricLogin()
                                    } catch (e: Exception) {
                                        bioStatus = "Couldn't verify that username and password."
                                    }
                                    bioBusy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)
                        ) { Text("Enable biometric sign-in", fontWeight = FontWeight.Bold) }
                    }
                }
                if (bioStatus.isNotEmpty()) {
                    Text(bioStatus, color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
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
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SeerrTitle>>(emptyList()) }
    var requests by remember { mutableStateOf<List<SeerrRequest>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(SeerrApi.isConnected()) }
    val colors = appFieldColors()

    LaunchedEffect(connected) {
        if (connected) requests = runCatching { SeerrApi.myRequests() }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Request Movies & TV", back)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ALLERVUE REQUESTS", color = Color(0xFFD8B77E), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Search the catalog and request titles for your library.", color = Grey, fontSize = 13.sp)
                }
                Text(if (connected) "CONNECTED" else "NOT CONNECTED",
                    color = if (connected) Color(0xFF72C99A) else Grey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))

            if (!connected) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF171719), RoundedCornerShape(14.dp)).padding(16.dp)
                ) {
                    Text("Finish connecting your account", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Allervue connects your request account automatically when you sign in. If you were already signed in before this update, sign out of Allervue and sign in again once. You won't need a separate request login.",
                        color = Grey, fontSize = 12.sp
                    )
                }
            } else {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search movies and TV shows") }, singleLine = true,
                    colors = colors, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !busy && query.trim().length >= 2,
                    onClick = {
                        busy = true; status = ""
                        scope.launch {
                            try { results = SeerrApi.search(query); if (results.isEmpty()) status = "No matching titles found." }
                            catch (e: Exception) { status = e.message ?: "Search failed." }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) { Text(if (busy) "Searching…" else "Search Allervue", color = Color.White) }

                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("SEARCH RESULTS", color = Color(0xFFD8B77E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    results.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .background(Color(0xFF171719), RoundedCornerShape(12.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.posterPath.isNotBlank()) {
                                coil.compose.AsyncImage(
                                    model = "https://image.tmdb.org/t/p/w185${item.posterPath}",
                                    contentDescription = item.title,
                                    modifier = Modifier.width(58.dp).height(86.dp).clip(RoundedCornerShape(7.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text((if (item.year.isNotBlank()) item.year + " · " else "") +
                                    if (item.mediaType == "movie") "Movie" else "TV Series",
                                    color = Grey, fontSize = 11.sp)
                                if (item.overview.isNotBlank()) Text(item.overview, color = Dim, fontSize = 11.sp,
                                    maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                                if (item.requestStatus.isNotBlank()) {
                                    Text("Already in library or requested · ${item.requestStatus}", color = Color(0xFFD8B77E), fontSize = 10.sp)
                                } else {
                                    Button(
                                        enabled = !busy,
                                        onClick = {
                                            busy = true; status = "Submitting ${item.title}…"
                                            scope.launch {
                                                try {
                                                    val message = SeerrApi.request(item)
                                                    status = "✓ ${item.title}: $message"
                                                    results = results.map { if (it.id == item.id && it.mediaType == item.mediaType) it.copy(requestStatus = "Requested") else it }
                                                    requests = runCatching { SeerrApi.myRequests() }.getOrDefault(requests)
                                                } catch (e: Exception) { status = e.message ?: "Request failed." }
                                                busy = false
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Red)
                                    ) { Text("Request", fontSize = 11.sp, color = Color.White) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("YOUR REQUESTS", color = Color(0xFFD8B77E), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch { requests = runCatching { SeerrApi.myRequests() }.getOrDefault(requests) }
                    }) { Text("Refresh", color = Grey) }
                }
                if (requests.isEmpty()) {
                    Text("No recent requests found.", color = Dim, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 10.dp))
                }
                requests.forEach { request ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .background(Color(0xFF171719), RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.width(64.dp).height(94.dp).clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF29292D)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (request.posterPath.isNotBlank()) {
                                coil.compose.AsyncImage(
                                    model = "https://image.tmdb.org/t/p/w185${request.posterPath}",
                                    contentDescription = request.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Text("NO\nPOSTER", color = Dim, fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold, lineHeight = 11.sp)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(request.title, color = Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(if (request.mediaType.equals("tv", true) || request.mediaType.equals("series", true))
                                "TV Series" else "Movie", color = Grey, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 3.dp))
                            Spacer(Modifier.height(8.dp))
                            val stateColor = when (request.requestStatus) {
                                "Approved" -> Color(0xFF83C99A)
                                "Declined" -> Color(0xFFFF8585)
                                else -> Color(0xFFD8B77E)
                            }
                            Text(
                                request.requestStatus.uppercase(),
                                color = stateColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp))
                                    .background(stateColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                            if (request.availabilityStatus.isNotBlank()) {
                                Text(request.availabilityStatus, color = Grey, fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    SeerrApi.disconnect(ctx)
                    connected = false
                    results = emptyList()
                    status = "Allervue disconnected."
                }) { Text("Disconnect Allervue", color = Grey) }
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(status, color = if (status.contains("failed", true) || status.contains("couldn't", true) || status.contains("HTTP")) Color(0xFFFF7777) else Grey,
                    fontSize = 12.sp)
            }
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

    Column(Modifier.fillMaxSize()) {
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


@Composable
fun ProfileScreen(
    back: () -> Unit,
    onServer: () -> Unit,
    onPortal: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Profile & account", back)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF171925))
                .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(22.dp))
                .padding(20.dp)
        ) {
            Text(Api.username.ifBlank { "Jellyfin user" }, color = Color.White,
                fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("Signed in to Allervue", color = Color(0xFF9CA6B5), fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Text("JELLYFIN SERVER", color = Color(0xFF65D6D0), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(Api.base, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.padding(top = 5.dp))
            Text("USER ID", color = Color(0xFF65D6D0), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 16.dp))
            Text(Api.userId.ifBlank { "Unavailable" }, color = Color(0xFFB6BFCC),
                fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            Text("REQUESTS", color = Color(0xFF65D6D0), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 16.dp))
            Text(if (SeerrApi.isConnected()) "Request account connected" else "Request account not connected",
                color = if (SeerrApi.isConnected()) Color(0xFF72D6A2) else Color(0xFFB6BFCC),
                fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
        }
        SettingsRow("⚙", "Server & security", "Server address, password and biometric sign-in", onServer)
        SettingsRow("↗", "Account portal", "Manage your account details online", onPortal)
        SettingsRow("⎋", "Sign out", "Sign out of this Jellyfin account", onSignOut)
        Text("Your profile details are read from the active Jellyfin session. Passwords and access tokens are not displayed.",
            color = Color(0xFF7F8998), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
    }
}

@Composable
fun AppearanceSettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("allervue_appearance", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM") }
    val options = listOf("SYSTEM" to "Follow device", "DARK" to "Dark", "LIGHT" to "Light")
    Column(Modifier.fillMaxSize().background(BrandInk).verticalScroll(rememberScrollState())) {
        ScreenHeader("Appearance", back)
        Text("THEME", color = BrandGold, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp))\n                    .background(BrandSurface).border(1.dp, if (mode == value) BrandGold.copy(alpha = .55f) else BrandGold.copy(alpha = .10f), RoundedCornerShape(18.dp)).clickable {
                    mode = value
                    prefs.edit().putString("theme_mode", value).apply()
                    (ctx as? android.app.Activity)?.recreate()
                }.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = BrandIvory, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(when (value) {
                        "DARK" -> "Always use the dark Allervue interface"
                        "LIGHT" -> "Always use the light Allervue interface"
                        else -> "Match your Android display setting"
                    }, color = Grey, fontSize = 12.sp)
                }
                RadioButton(selected = mode == value, onClick = {
                    mode = value
                    prefs.edit().putString("theme_mode", value).apply()
                    (ctx as? android.app.Activity)?.recreate()
                }, colors = RadioButtonDefaults.colors(selectedColor = BrandGoldBright, unselectedColor = Grey))
            }
            Spacer(Modifier.height(5.dp))
        }
        Text("The selected theme is saved on this device and applied immediately.",
            color = Grey, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
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
    Column(Modifier.fillMaxSize()) {
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


@Composable
fun DiagnosticScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var log by remember { mutableStateOf(DiagnosticLog.read(ctx)) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Diagnostics", back)
        Text("If Allervue closes when you tap a video, reproduce the issue and return here. The log records player startup and crash details without your Jellyfin password or access token.",
            color = Grey, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { log = DiagnosticLog.read(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29272A))) {
                Text("Refresh log", color = Color.White)
            }
            Button(onClick = {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Allervue diagnostics", log.ifBlank { "No diagnostic log yet." }))
                android.widget.Toast.makeText(ctx, "Diagnostic log copied", android.widget.Toast.LENGTH_SHORT).show()
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29272A))) { Text("Copy", color = Color.White) }
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Allervue diagnostic log")
                    putExtra(Intent.EXTRA_TEXT, log.ifBlank { "No diagnostic log yet." })
                }
                ctx.startActivity(Intent.createChooser(send, "Share diagnostic log"))
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8B77E))) { Text("Share", color = Color(0xFF171515)) }
        }
        Text(if (log.isBlank()) "No diagnostic entries yet. Play a video, then return and tap Refresh log." else log,
            color = Color(0xFFD5D1CA), fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()))
    }
}
