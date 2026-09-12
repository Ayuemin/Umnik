from pathlib import Path

vm_path = Path('app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt')
vm = vm_path.read_text(encoding='utf-8')
vm = vm.replace(
    '    private fun currentTextModelId(): String =\n\n    private fun currentTextModelId(): String =',
    '    private fun currentTextModelId(): String =',
    1,
)
vm_path.write_text(vm, encoding='utf-8')

ui_path = Path('app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt')
ui = ui_path.read_text(encoding='utf-8')
connections_end = '''            item {\n                Column(\n                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),'''
ui = ui.replace(connections_end.rstrip() + '\n\n' + connections_end, connections_end, 1)
picker_end = '''@Composable\nprivate fun EmptyChatCard(mode: ChatMode) {'''
ui = ui.replace(picker_end.rstrip() + '\n\n' + picker_end, picker_end, 1)
ui_path.write_text(ui, encoding='utf-8')

print('Umnik v1.3.0 patch normalization complete')
