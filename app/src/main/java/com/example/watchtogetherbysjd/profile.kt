package com.example.watchtogetherbysjd

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AVATARS = listOf(
    "😎", "🙂", "😺", "🐶", "🦊", "🐼",
    "🦁", "🐸", "🚀", "👻", "🎮", "🍿"
)

class UserProfile(
    val name: String,
    val avatar: String,
    val isHost: Boolean = false
)

// Saves the profile on this phone so you only create it once
object ProfileStore {
    private const val PREFS = "profile"

    fun load(context: Context): UserProfile? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = sp.getString("name", null) ?: return null
        val avatar = sp.getString("avatar", "🙂") ?: "🙂"
        return UserProfile(name, avatar)
    }

    fun save(context: Context, p: UserProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("name", p.name)
            .putString("avatar", p.avatar)
            .apply()
    }
}

@Composable
fun ProfileDialog(
    initial: UserProfile?,
    required: Boolean,
    onSave: (UserProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var avatar by remember { mutableStateOf(initial?.avatar ?: AVATARS.first()) }

    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        title = { Text(if (required) "Create your profile" else "Edit profile") },
        text = {
            Column {
                Text(
                    "Friends will see this name and picture in chat and in the room.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { input ->
                        name = input
                            .filter { c -> c.isLetterOrDigit() || c == ' ' || c == '_' || c == '.' }
                            .take(16)
                    },
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Pick a picture", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(6.dp))
                AVATARS.chunked(6).forEach { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        rowItems.forEach { a ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        if (a == avatar) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                        CircleShape
                                    )
                                    .clickable { avatar = a },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(a, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().length >= 2,
                onClick = { onSave(UserProfile(name.trim(), avatar)) }
            ) { Text("Save") }
        },
        dismissButton = {
            if (!required) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}