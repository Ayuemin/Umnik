from pathlib import Path
import re

path = Path(__file__).with_name("apply_v1_3_6.py")
text = path.read_text(encoding="utf-8")

pattern = re.compile(
    r'''registry = replace_once\(\n    registry,\n    ''' + "'''" + r'''                    ImageModelDefinition\(\\n.*?    "remove unstable NVIDIA schnell fallback",\n\)''',
    re.S,
)
replacement = '''registry = replace_once(
    registry,
    """                    ImageModelDefinition(
                        \"black-forest-labs/flux.1-schnell\",
                        parameterOptions = mapOf(\"aspect_ratio\" to COMMON_RATIOS)
                    ),
""",
    "",
    "remove unstable NVIDIA schnell fallback",
)'''

updated, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise RuntimeError(f"Could not repair provider registry matcher: {count} matches")
path.write_text(updated, encoding="utf-8")
print("v1.3.6 patch preflight complete")
