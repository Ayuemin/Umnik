from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)

# Fix Kotlin regex escaping in ChatViewModel.kt.
p = Path('app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '.replace(Regex("[^\\p{L}\\p{N}._ ()-]"), "_")',
    '.replace(Regex("[^\\\\p{L}\\\\p{N}._ ()-]"), "_")',
    'filename regex escaping'
)
p.write_text(s, encoding='utf-8')

# Fix Settings footer/braces and avoid BuildConfig dependency.
p = Path('app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('import com.ayuemin.ymnik.BuildConfig\n', '', 1)
s = replace_once(
    s,
    '''private fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }''',
    '''private fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }
    var key by remember { mutableStateOf("") }''',
    'settings app version'
)
s = s.replace('"Версия ${BuildConfig.VERSION_NAME}"', '"Версия $appVersion"', 1)
s = replace_once(
    s,
    '''                }
        }
    }

    if (storageOpen) StorageDialog''',
    '''                }
            }
        }
    }

    if (storageOpen) StorageDialog''',
    'settings closing braces'
)
p.write_text(s, encoding='utf-8')
