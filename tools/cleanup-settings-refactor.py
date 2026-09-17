from pathlib import Path

path = Path("app/src/main/java/com/ayuemin/ymnik/ui/SettingsScreen.kt")
text = path.read_text(encoding="utf-8")

old_state = '    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }\n'
old_dialog = '''    modelPicker?.let { mode ->
        ModelPickerDialog(mode = mode, state = state, vm = vm, onDismiss = { modelPicker = null })
    }
'''

if old_state not in text or old_dialog not in text:
    raise SystemExit("Expected stale ModelPicker settings references were not found")

text = text.replace(old_state, "", 1)
text = text.replace(old_dialog, "", 1)
path.write_text(text, encoding="utf-8")
