from pathlib import Path

path = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
text = path.read_text(encoding="utf-8")

old_import = "import java.security.MessageDigest\n"
if old_import in text:
    text = text.replace(old_import, "", 1)

marker = "private fun stableServerRequestId(payloadJson: String): String {"
if text.count(marker) != 1:
    raise SystemExit(f"Expected one stableServerRequestId, found {text.count(marker)}")
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
    raise SystemExit("Could not find stableServerRequestId end")

replacement = "private fun stableServerRequestId(payloadJson: String): String =\n    ServerRequestIdentity.build(requestId, payloadJson)"
text = text[:start] + replacement + text[end:]

if "getAsJsonObject(\"metadata\")?.remove(\"umnik_request_id\")" in text:
    raise SystemExit("Old collision-prone server request identity logic remains")

path.write_text(text, encoding="utf-8")
print("Server request identity now uses runtime request id plus full step payload")
