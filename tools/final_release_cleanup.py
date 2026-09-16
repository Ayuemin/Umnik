from pathlib import Path

# Keep release notes user-facing and OpenRouter-only.
changelog = Path("CHANGELOG.md")
text = changelog.read_text(encoding="utf-8")
old = '''- Ограничение числа одновременных вызовов OpenRouter по умолчанию отключено (`0 = без ограничений`); архитектура не вводит собственного фиксированного потолка.
- Многозадачная ветка v1.17.0 работает через OpenRouter; отдельная поддержка NVIDIA в этой архитектуре не используется.
'''
new = '''- Все модельные вызовы многозадачной архитектуры выполняются через OpenRouter. Собственного фиксированного лимита параллельных чатов Umnik не вводит; фактические ограничения определяются OpenRouter, выбранными моделями, сетью и устройством.
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"release-note cleanup: expected 1 occurrence, got {count}")
changelog.write_text(text.replace(old, new, 1), encoding="utf-8")

# One-off migration/build scaffolding must not enter main.
temporary = [
    ".github/workflows/apply-multi-request-v1170.yml",
    ".github/workflows/orchestrator-parallel-v1170.yml",
    ".github/workflows/multi-request-ui-v1170.yml",
    ".github/workflows/prepare-v1170.yml",
    ".github/workflows/finalize-v1170.yml",
    "tools/add_orchestrator_parallelism.py",
    "tools/apply_multi_request_core.py",
    "tools/finalize_multi_request_openrouter.py",
    "tools/finalize_multi_request_ui.py",
    "tools/fix_orchestrator_parallelism_patch.py",
    "tools/prepare_multi_request_migration.py",
    "tools/prepare_v1170_release.py",
    "tools/final_release_cleanup.py",
]

for name in temporary:
    path = Path(name)
    if path.exists():
        path.unlink()

print("clean v1.17.0 tree prepared")
