from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

client = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/LocalShellAgentClient.kt"
prefs = ROOT / "app/src/main/java/com/ayuemin/ymnik/data/OpenRouterFeaturePrefs.kt"
events = ROOT / "app/src/main/java/com/ayuemin/ymnik/AsyncJobEvents.kt"
hub = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("PATCHED", label, path.relative_to(ROOT))

# Runtime: 500 is a true emergency ceiling even if a stale/corrupt preference is higher.
replace_once(
    client,
    "val safeMaxTurns = maxTurns.coerceAtLeast(1)",
    "val safeMaxTurns = maxTurns.coerceIn(1, HARD_MAX_TURNS)",
    "runtime hard cap"
)
replace_once(
    client,
    'private const val DEFAULT_MAX_TURNS = 500\n        private const val MIN_TOOL_CALLS = 64',
    'private const val DEFAULT_MAX_TURNS = 500\n        private const val HARD_MAX_TURNS = 500\n        private const val MIN_TOOL_CALLS = 64',
    "runtime hard cap constant"
)

# Preferences: new installations default to 500. Devices still carrying the old implicit
# default 24 are migrated once. Explicit user choices such as 100 remain respected.
old_prefs = '''    fun localShellMaxTurns(): Int = prefs.getInt("local_shell_max_turns", 24).coerceAtLeast(1)
    fun saveLocalShellMaxTurns(value: Int) {
        prefs.edit().putInt("local_shell_max_turns", value.coerceAtLeast(1)).apply()
    }
'''
new_prefs = '''    fun localShellMaxTurns(): Int {
        val stored = prefs.getInt("local_shell_max_turns", 500)
        val value = if (stored == 24) 500 else stored.coerceIn(1, 500)
        if (value != stored) prefs.edit().putInt("local_shell_max_turns", value).apply()
        return value
    }
    fun saveLocalShellMaxTurns(value: Int) {
        prefs.edit().putInt("local_shell_max_turns", value.coerceIn(1, 500)).apply()
    }
'''
replace_once(prefs, old_prefs, new_prefs, "preference default and migration")

# Activity fallbacks are display/runtime safety defaults for callers that omit the value.
events_text = events.read_text(encoding="utf-8")n
