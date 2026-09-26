from pathlib import Path

p = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
s = p.read_text()
old_import = "import kotlinx.coroutines.Dispatchers\n"
new_import = "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\n"
if s.count(old_import) != 1:
    raise SystemExit(f"Cancellation import anchor: expected 1, found {s.count(old_import)}")
s = s.replace(old_import, new_import, 1)

start = s.index('                    "local_shell_start" -> {')
end = s.index('                    "local_shell_status" -> {', start)
branch = s[start:end]
old = '''                            }.getOrElse {
                                gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Не удалось запустить Local Shell")))
                            }
'''
new = '''                            }.getOrElse {
                                if (it is CancellationException) throw it
                                gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Не удалось запустить Local Shell")))
                            }
'''
if branch.count(old) != 1:
    raise SystemExit(f"local_shell_start cancellation anchor: expected 1, found {branch.count(old)}")
branch = branch.replace(old, new, 1)
s = s[:start] + branch + s[end:]
p.write_text(s)

p = Path("app/src/test/java/com/ayuemin/ymnik/network/AgentLocalShellAwaitRegressionTest.kt")
s = p.read_text()
anchor = '        assertFalse(branch.contains("localShellStatus"))\n'
insert = anchor + '        assertTrue(branch.contains("if (it is CancellationException) throw it"))\n'
if s.count(anchor) != 1:
    raise SystemExit(f"regression anchor: expected 1, found {s.count(anchor)}")
p.write_text(s.replace(anchor, insert, 1))
