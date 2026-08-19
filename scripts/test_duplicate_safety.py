from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def resource_keys(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {element.attrib["name"] for element in root if element.tag in {"string", "plurals"}}


def main() -> None:
    english = ROOT / "app/src/main/res/values/strings.xml"
    hindi = ROOT / "app/src/main/res/values-hi/strings.xml"
    english_keys = resource_keys(english)
    hindi_keys = resource_keys(hindi)
    assert english_keys == hindi_keys, (english_keys - hindi_keys, hindi_keys - english_keys)

    manager = (ROOT / "app/src/main/java/com/example/data/DuplicateManager.kt").read_text()
    assert "getDuplicateFilesByHash" in manager
    assert "exactDuplicateIds" in manager

    compat = (ROOT / "app/src/main/java/com/example/ui/MainViewModelCompat.kt").read_text()
    assert "level3VisualDuplicates.value + videoDuplicates.value + semanticDuplicates.value" not in compat

    screen = (ROOT / "app/src/main/java/com/example/ui/screens/AiDuplicatesScreen.kt").read_text()
    assert screen.count("selectable = false") >= 4
    assert "duplicate_review_only" in screen

    worker = (ROOT / "app/src/main/java/com/example/worker/DuplicateCleanupWorker.kt").read_text()
    assert "md5Hash.isNotBlank()" in worker
    assert "visualSimilarityHash" not in worker
    print("duplicate safety validation passed")


if __name__ == "__main__":
    main()
