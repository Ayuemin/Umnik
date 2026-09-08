from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = path.read_text(encoding="utf-8")

old = '''    fun setReasoningEffort(effort: ReasoningEffort) {\n        prefs.edit().putString("reasoning_effort", effort.name).apply()\n        _state.value = _state.value.copy(reasoningEffort = effort)\n    }'''
new = '''    fun setReasoningEffort(effort: ReasoningEffort) {\n        val info = currentTextModelInfo()\n        val keepReasoning = _state.value.reasoningEnabled && info?.supportsReasoning == true &&\n            (info.reasoningEfforts.isEmpty() || effort.apiValue in info.reasoningEfforts)\n        prefs.edit()\n            .putString("reasoning_effort", effort.name)\n            .putBoolean("reasoning_enabled", keepReasoning)\n            .apply()\n        _state.value = _state.value.copy(reasoningEffort = effort, reasoningEnabled = keepReasoning)\n    }'''
if old not in text:
    raise RuntimeError("setReasoningEffort pattern not found")
text = text.replace(old, new, 1)

old = '''                        val actualReasoning = reasoningEnabled && modelInfo?.supportsReasoning == true\n                        val effort = if (actualReasoning && modelInfo?.supportsReasoningEffort == true) reasoningEffort.apiValue else null'''
new = '''                        val actualReasoning = reasoningEnabled && modelInfo?.supportsReasoning == true &&\n                            (modelInfo.reasoningEfforts.isEmpty() || reasoningEffort.apiValue in modelInfo.reasoningEfforts)\n                        val effort = if (actualReasoning && modelInfo.supportsReasoningEffort) reasoningEffort.apiValue else null'''
if old not in text:
    raise RuntimeError("send reasoning pattern not found")
text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Reasoning compatibility hardened")
