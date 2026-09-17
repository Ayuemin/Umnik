from pathlib import Path

path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
text = path.read_text(encoding="utf-8")

imports_to_remove = [
    "import android.media.AudioAttributes\n",
    "import android.media.AudioManager\n",
    "import android.media.MediaPlayer\n",
    "import android.media.ToneGenerator\n",
    "import android.os.Handler\n",
    "import android.os.Looper\n",
]
for item in imports_to_remove:
    if text.count(item) != 1:
        raise SystemExit(f"Expected one import, found {text.count(item)}: {item.strip()}")
    text = text.replace(item, "", 1)

import_anchor = "import com.ayuemin.ymnik.data.StorageRepository\n"
if text.count(import_anchor) != 1:
    raise SystemExit("StorageRepository import anchor not found exactly once")
text = text.replace(
    import_anchor,
    import_anchor + "import com.ayuemin.ymnik.audio.AnswerSoundPlayer\n",
    1,
)

field_anchor = "    private val storageRepository = StorageRepository(context)\n"
if text.count(field_anchor) != 1:
    raise SystemExit("StorageRepository field anchor not found exactly once")
text = text.replace(
    field_anchor,
    field_anchor + "    private val answerSoundPlayer = AnswerSoundPlayer()\n",
    1,
)

marker = "    private fun playReadySound() {"
if text.count(marker) != 1:
    raise SystemExit(f"Expected exactly one playReadySound implementation, found {text.count(marker)}")
start = text.index(marker)
brace_start = text.index("{", start)
depth = 0
end = None
for index in range(brace_start, len(text)):
    char = text[index]
    if char == "{":
        depth += 1
    elif char == "}":
        depth -= 1
        if depth == 0:
            end = index + 1
            break
if end is None:
    raise SystemExit("Could not find end of playReadySound")

replacement = "    private fun playReadySound() {\n        answerSoundPlayer.play(_state.value)\n    }"
text = text[:start] + replacement + text[end:]

for token in ["MediaPlayer()", "ToneGenerator(", "AudioAttributes.Builder()", "Handler(Looper.getMainLooper())"]:
    if token in text:
        raise SystemExit(f"Android audio implementation remains in ChatViewModel: {token}")

path.write_text(text, encoding="utf-8")
print("Answer sound playback extracted from ChatViewModel")
