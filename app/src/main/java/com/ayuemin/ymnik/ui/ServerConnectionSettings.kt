package com.ayuemin.ymnik.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.data.RequestRouteMode
import com.ayuemin.ymnik.data.ServerConnectionStore
import com.ayuemin.ymnik.network.UmnikServerClient
import kotlinx.coroutines.launch

@Composable
fun ServerConnectionSettings() {
    val context = LocalContext.current
    val store = remember(context) { ServerConnectionStore(context.applicationContext) }
    val client = remember { UmnikServerClient() }
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(store.config()) }
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var token by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text("Режим работы", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.mode == RequestRouteMode.DIRECT,
                onClick = {
                    store.setMode(RequestRouteMode.DIRECT)
                    config = store.config()
                },
                label = { Text("Напрямую") },
                leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) }
            )
            FilterChip(
                selected = config.mode == RequestRouteMode.SERVER,
                onClick = {
                    val saved = store.config()
                    if (saved.baseUrl.isBlank() || !saved.tokenConfigured) {
                        Toast.makeText(context, "Сначала сохраните адрес и токен личного сервера", Toast.LENGTH_SHORT).show()
                    } else {
                        store.setMode(RequestRouteMode.SERVER)
                        config = store.config()
                    }
                },
                label = { Text("Через сервер") },
                leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null) }
            )
        }

        Text(
            if (config.mode == RequestRouteMode.SERVER)
                "Текстовые запросы выполняет ваш личный сервер. Телефон только отправляет задачу и получает результат."
            else
                "Обычный режим: Umnik обращается к OpenRouter прямо с телефона.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it.trim().take(300) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Адрес личного сервера") },
            placeholder = { Text("https://umnik.example.com:8443") },
            supportingText = {
                Text("Можно указать нестандартный HTTPS-порт после адреса, например :8443. Без порта используется стандартный 443.")
            },
            singleLine = true
        )
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.take(256) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Токен сервера") },
            placeholder = {
                Text(if (config.tokenConfigured) "Сохранён · оставьте пустым, чтобы не менять" else "Обязателен")
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = {
                store.saveServer(baseUrl, token.takeIf { it.isNotBlank() })
                token = ""
                config = store.config()
                Toast.makeText(context, "Настройки личного сервера сохранены", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = baseUrl.isNotBlank()
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null)
            Text(" Сохранить сервер")
        }
        Spacer(Modifier.height(6.dp))
        FilledTonalButton(
            onClick = {
                val saved = store.config()
                val savedToken = store.token()
                if (saved.baseUrl.isBlank() || savedToken.isNullOrBlank()) {
                    Toast.makeText(context, "Сначала сохраните адрес и токен сервера", Toast.LENGTH_SHORT).show()
                    return@FilledTonalButton
                }
                checking = true
                scope.launch {
                    val message = runCatching {
                        check(client.health(saved.baseUrl)) { "Сервер не отвечает" }
                        val capabilities = client.capabilities(saved.baseUrl, savedToken)
                        check(capabilities.protocol == 1) { "Неподдерживаемая версия протокола: ${capabilities.protocol}" }
                        check(capabilities.durable_chat_jobs && capabilities.idempotent_client_request_id) {
                            "Сервер не поддерживает безопасные фоновые задания"
                        }
                        "Связь с личным сервером работает"
                    }.getOrElse { it.message ?: "Не удалось проверить сервер" }
                    checking = false
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !checking && config.baseUrl.isNotBlank() && config.tokenConfigured
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Text(if (checking) " Проверяю…" else " Проверить сервер")
        }
        Text(
            "Через сервер пока направляется только обычный текстовый чат. Изображения, аудио и служебные запросы идут напрямую.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
