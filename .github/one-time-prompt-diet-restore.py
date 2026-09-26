from pathlib import Path

p = Path("app/src/main/java/com/ayuemin/ymnik/PromptDiet.kt")
s = p.read_text()
replacements = [
    (
        '"Local Shell — асинхронный локальный исполнитель на устройстве пользователя. Он может работать после завершения твоего текущего ответа, а пользователь может продолжать этот же диалог." to "Local Shell асинхронен для UI; после local_shell_start Umnik сам ждёт итог worker без model/status-поллинга.",',
        '"Local Shell — асинхронный локальный исполнитель на устройстве пользователя. Он может работать после завершения твоего текущего ответа, а пользователь может продолжать этот же диалог." to "Local Shell — асинхронный локальный исполнитель; он может работать после твоего ответа, пока диалог продолжается.",'
    ),
    (
        '"Если Local Shell уже работает в этом чате, не запускай второй. Используй local_shell_status для проверки состояния, local_shell_note для передачи нового ограничения/уточнения пользователя, local_shell_stop — только по явной просьбе остановить." to "Если Shell работал до текущего шага, второй не запускай: status — отдельная проверка, note — уточнение, stop — явная остановка. После собственного local_shell_start status не опрашивай: итог вернётся этим же tool-вызовом.",',
        '"Если Local Shell уже работает в этом чате, не запускай второй. Используй local_shell_status для проверки состояния, local_shell_note для передачи нового ограничения/уточнения пользователя, local_shell_stop — только по явной просьбе остановить." to "Если Shell уже работает, второй не запускай: status — проверить, note — передать уточнение, stop — только по явной просьбе остановить.",'
    )
]
for old, new in replacements:
    if s.count(old) != 1:
        raise SystemExit(f"Expected exactly one Prompt Diet match, found {s.count(old)}")
    s = s.replace(old, new, 1)
p.write_text(s)
