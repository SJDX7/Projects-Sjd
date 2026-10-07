package com.example.watchtogetherbysjd

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ChatMessage(
    val id: String,
    val by: String,
    val name: String,
    val avatar: String,
    val isHost: Boolean,
    val text: String
)

private val NAME_COLORS = listOf(
    Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784),
    Color(0xFFFFB74D), Color(0xFFBA68C8), Color(0xFF4DB6AC)
)

@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    myId: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sidePanel: Boolean = false
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Always scroll to the newest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Surface(
        modifier = modifier,
        shape = if (sidePanel) {
            RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        },
        color = if (sidePanel) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sidePanel) Modifier.fillMaxHeight() else Modifier.height(300.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chat", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text("Close") }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "No messages yet. Say hi!",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                items(messages, key = { it.id }) { m ->
                    ChatBubble(m, m.by == myId)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(300) },
                    placeholder = { Text("Message") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onSend(input)
                        input = ""
                    }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSend(input)
                        input = ""
                    },
                    enabled = input.isNotBlank()
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ChatBubble(m: ChatMessage, mine: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (!mine) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.avatar, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    m.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = NAME_COLORS[(m.by.hashCode() and 0x7fffffff) % NAME_COLORS.size]
                )
                if (m.isHost) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "HOST",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Text(
                m.text,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}