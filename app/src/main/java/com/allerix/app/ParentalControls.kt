package com.allerix.app

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Device-local parental policy. The PIN is salted and hashed; the plaintext PIN is never persisted.
 * Playback enforcement is deliberately independent from the Jellyfin account password.
 */
object ParentalControls {
    private const val PREFS = "parental_controls"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MAX_RATING = "max_rating"
    private const val KEY_BLOCK_UNRATED = "block_unrated"

    val ratings = listOf("G", "PG", "PG-13", "R", "NC-17", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "TV-14", "TV-MA")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun hasPin(context: Context) = prefs(context).contains(KEY_HASH)
    fun maxRating(context: Context) = prefs(context).getString(KEY_MAX_RATING, "PG-13") ?: "PG-13"
    fun blocksUnrated(context: Context) = prefs(context).getBoolean(KEY_BLOCK_UNRATED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setPolicy(context: Context, rating: String, blockUnrated: Boolean) {
        prefs(context).edit().putString(KEY_MAX_RATING, rating)
            .putBoolean(KEY_BLOCK_UNRATED, blockUnrated).apply()
    }

    fun setPin(context: Context, pin: String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = hash(pin, salt)
        prefs(context).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(digest, Base64.NO_WRAP))
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString(KEY_SALT, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: return false
        val expected = p.getString(KEY_HASH, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: return false
        return MessageDigest.isEqual(expected, hash(pin, salt))
    }

    fun clearPin(context: Context) {
        prefs(context).edit().remove(KEY_SALT).remove(KEY_HASH).putBoolean(KEY_ENABLED, false).apply()
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var value = pin.toByteArray(Charsets.UTF_8) + salt
        repeat(120_000) { value = digest.digest(value) }
        return value
    }

    private fun ratingRank(value: String): Int? {
        val normalized = value.trim().uppercase().replace(" ", "")
        return when (normalized) {
            "G", "TV-Y", "TV-G" -> 0
            "TV-Y7" -> 1
            "PG", "TV-PG" -> 2
            "PG-13", "TV-14" -> 3
            "R", "TV-MA" -> 4
            "NC-17" -> 5
            else -> null
        }
    }

    fun requiresPin(context: Context, item: Item): Boolean {
        if (!isEnabled(context)) return false
        val official = item.officialRating?.trim().orEmpty()
        if (official.isBlank()) return blocksUnrated(context)
        val contentRank = ratingRank(official) ?: return blocksUnrated(context)
        val limitRank = ratingRank(maxRating(context)) ?: 2
        return contentRank > limitRank
    }
}

@Composable
fun ParentalControlsScreen(back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(ParentalControls.isEnabled(context)) }
    var selectedRating by remember { mutableStateOf(ParentalControls.maxRating(context)) }
    var blockUnrated by remember { mutableStateOf(ParentalControls.blocksUnrated(context)) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf(!ParentalControls.hasPin(context)) }
    var setupMode by remember { mutableStateOf(!ParentalControls.hasPin(context)) }
    var message by remember { mutableStateOf("") }

    fun savePolicy() {
        ParentalControls.setPolicy(context, selectedRating, blockUnrated)
        ParentalControls.setEnabled(context, enabled)
        refresh++
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = MaterialTheme.colorScheme.onBackground, fontSize = 32.sp,
                modifier = Modifier.clickable(onClick = back).padding(end = 14.dp))
            Column {
                Text("Parental Controls", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Protect age-restricted titles", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(18.dp)) {
                    Text("PARENT PIN", color = AllervueDesign.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(
                        if (setupMode) "Create your parent PIN" else if (verified) "Parent access verified" else "Enter your parent PIN to manage restrictions",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 7.dp)
                    )
                    if (!verified) {
                        OutlinedTextField(
                            value = verification, onValueChange = { verification = it.filter(Char::isDigit).take(8) },
                            label = { Text("Parent PIN") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        )
                        Button(onClick = {
                            if (ParentalControls.verifyPin(context, verification)) {
                                verified = true; verification = ""; message = ""
                            } else message = "Incorrect PIN."
                        }, modifier = Modifier.padding(top = 10.dp)) { Text("Unlock settings") }
                    } else if (setupMode) {
                        Text("Use 4–8 digits. This PIN is stored securely on this device as a salted hash.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("New PIN") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        OutlinedTextField(confirmPin, { confirmPin = it.filter(Char::isDigit).take(8) }, label = { Text("Confirm PIN") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Button(onClick = {
                            if (pin.length < 4) message = "PIN must contain at least 4 digits."
                            else if (pin != confirmPin) message = "PINs do not match."
                            else {
                                ParentalControls.setPin(context, pin)
                                enabled = true; verified = true; setupMode = false
                                pin = ""; confirmPin = ""; message = "Parent PIN created."
                            }
                        }, modifier = Modifier.padding(top = 10.dp)) { Text("Create PIN") }
                    } else {
                        Text("Your parent PIN protects changes to these settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = { setupMode = true; pin = ""; confirmPin = ""; message = "" }) { Text("Change PIN") }
                    }
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (verified && !setupMode) {
                item {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Enable parental controls", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                Text("Require the parent PIN for restricted titles", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(enabled, { enabled = it; savePolicy() })
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text("Highest allowed rating", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Text("Titles above this rating will require the parent PIN.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text(selectedRating); Text("  ▾") }
                            DropdownMenu(expanded, { expanded = false }) {
                                ParentalControls.ratings.forEach { rating ->
                                    DropdownMenuItem(text = { Text(rating) }, onClick = {
                                        selectedRating = rating; expanded = false; savePolicy()
                                    })
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Restrict unrated titles", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                Text("Unknown or missing ratings will also require the PIN.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(blockUnrated, { blockUnrated = it; savePolicy() })
                        }
                        TextButton(onClick = {
                            ParentalControls.clearPin(context)
                            enabled = false; verified = true; setupMode = true
                            message = "Parental PIN removed."
                        }) { Text("Remove parent PIN", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Text("Ratings are based on the official rating supplied by your Jellyfin server. This first release applies restrictions on this device; it does not change the server's user policy.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}
