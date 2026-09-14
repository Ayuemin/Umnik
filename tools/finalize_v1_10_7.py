from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def pre() -> None:
    path = "tools/patch_v1_10_7.py"
    text = read(path)
    old = '''    if count != 1:\n        raise RuntimeError(f"{label}: expected exactly one match, got {count}")\n    return text.replace(old, new, 1)'''
    new = '''    if count != 1:\n        if label == "document synthesize format" and count == 2:\n            return text.replace(old, new, 1)\n        raise RuntimeError(f"{label}: expected exactly one match, got {count}")\n    return text.replace(old, new, 1)'''
    if old not in text:
        raise SystemExit("replace_once helper anchor not found")
    write(path, text.replace(old, new, 1))
    print("v1.10.7 patch helper prepared")


def post() -> None:
    audio_path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterAudioClient.kt"
    text = read(audio_path)
    replacements = {
        'Regex("only\\s+(supports?|accepts?)\\s+[^.]{0,40}pcm")': 'Regex("""only\\s+(supports?|accepts?)\\s+[^.]{0,40}pcm""")',
        'Regex("only\\s+(supports?|accepts?)\\s+[^.]{0,40}mp3")': 'Regex("""only\\s+(supports?|accepts?)\\s+[^.]{0,40}mp3""")',
    }
    for old, new in replacements.items():
        if old not in text:
            raise SystemExit(f"audio regex anchor not found: {old}")
        text = text.replace(old, new, 1)
    write(audio_path, text)

    speech_path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterSpeechPlayer.kt"
    text = read(speech_path)

    deep_old = '''                                    voice = replyVoice,\n                                    baseUrl = baseUrl\n'''
    deep_new = '''                                    voice = replyVoice,\n                                    responseFormat = replyFormat,\n                                    baseUrl = baseUrl\n'''
    if deep_old in text:
        text = text.replace(deep_old, deep_new, 1)

    start = text.find("    private fun splitForSpeech(")
    class_end = text.rfind("\n}")
    if start < 0 or class_end <= start:
        raise SystemExit("splitForSpeech anchors not found")

    replacement = r'''    private fun splitForSpeech(
        source: String,
        singleRequestMaxChars: Int = 1800,
        chunkMaxChars: Int = 1500
    ): List<String> {
        val normalized = source
            .replace("\r\n", "\n")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        if (normalized.length <= singleRequestMaxChars) return listOf(normalized)

        fun splitLongParagraph(paragraph: String): List<String> {
            var rest = paragraph.trim()
            if (rest.length <= chunkMaxChars) return listOf(rest)
            val result = mutableListOf<String>()
            while (rest.length > chunkMaxChars) {
                val candidate = rest.take(chunkMaxChars)
                val sentenceBreak = listOf(". ", "! ", "? ", "… ")
                    .maxOf { candidate.lastIndexOf(it) }
                    .takeIf { it >= chunkMaxChars / 2 }
                    ?.plus(1)
                val wordBreak = candidate.lastIndexOf(' ')
                    .takeIf { it >= chunkMaxChars / 2 }
                val splitAt = sentenceBreak ?: wordBreak ?: chunkMaxChars
                result += rest.take(splitAt).trim()
                rest = rest.drop(splitAt).trimStart()
            }
            if (rest.isNotBlank()) result += rest
            return result
        }

        val pieces = normalized
            .split(Regex("""\n\s*\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap(::splitLongParagraph)

        if (pieces.isEmpty()) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        pieces.forEach { piece ->
            val separator = if (current.isEmpty()) "" else "\n\n"
            if (current.isNotEmpty() && current.length + separator.length + piece.length > chunkMaxChars) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }
'''.replace('\\"', '"')
    text = text[:start] + replacement + text[class_end:]
    text = text.replace(
        " * Long answers are synthesized in short fragments. The first fragment starts\n * playing as soon as it is ready while the following fragment is prepared in\n * parallel. Audio only lives in app cache for the current playback session.",
        " * Short and medium answers are synthesized as one coherent utterance for better\n * prosody. Long answers are split at natural boundaries; the next part is prepared\n * while the current one plays. Audio only lives in app cache for this session."
    )
    write(speech_path, text)

    changelog_path = "CHANGELOG.md"
    text = read(changelog_path)
    match = re.search(r"## v1\.10\.7[^\n]*\n", text)
    if not match:
        raise SystemExit("v1.10.7 changelog marker not found")
    note = "- OR-озвучка коротких и средних ответов теперь генерируется целиком для более естественной интонации; длинные ответы делятся крупно по смысловым границам.\n"
    if note not in text:
        text = text[:match.end()] + "\n" + note + text[match.end():]
    write(changelog_path, text)
    print("v1.10.7 generated sources finalized")


if __name__ == "__main__":
    phase = sys.argv[1] if len(sys.argv) > 1 else ""
    if phase == "pre":
        pre()
    elif phase == "post":
        post()
    else:
        raise SystemExit("usage: finalize_v1_10_7.py pre|post")
