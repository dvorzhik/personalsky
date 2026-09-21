"""Проверяет графику листинга Google Play на соответствие требованиям.

Запуск: python tools/check-store-assets.py <файл> [<файл> ...]

Зачем: Play Console отклоняет загрузку картинки с неверным размером или
с альфа-каналом там, где он запрещён, и говорит об этом уже на этапе
загрузки. Быстрее проверить локально, чем искать причину в интерфейсе.
"""
import sys
from pathlib import Path

from PIL import Image

# Требования Google Play к графике страницы приложения.
REQUIREMENTS = {
    "personal_sky_play_icon_512.png": ("Значок в Play Store", 512, 512, True),
    "personal_sky_feature_graphic_1024x500.png": ("Основное изображение", 1024, 500, False),
}

# Скриншоты телефона: короткая сторона от 320, длинная до 3840 пикселей.
SCREENSHOT_MIN_SIDE = 320
SCREENSHOT_MAX_SIDE = 3840


def describe_screenshot(path: Path) -> int:
    with Image.open(path) as image:
        width, height = image.size
        print(f"Скриншот телефона: {path.name}")
        print(f"  размер: {width}x{height}, режим: {image.mode}")
        if min(width, height) < SCREENSHOT_MIN_SIDE or max(width, height) > SCREENSHOT_MAX_SIDE:
            print(
                f"  [ОШИБКА] нужны стороны от {SCREENSHOT_MIN_SIDE} до"
                f" {SCREENSHOT_MAX_SIDE} пикселей"
            )
            return 1
        print("  [OK] соответствует требованиям")
        return 0


def describe(path: Path) -> int:
    name = path.name
    title, expect_width, expect_height, alpha_allowed = REQUIREMENTS[name]
    with Image.open(path) as image:
        width, height = image.size
        mode = image.mode
        has_alpha = mode in ("RGBA", "LA") or "transparency" in image.info

        print(f"{title}: {name}")
        print(f"  размер:      {width}x{height} (требуется {expect_width}x{expect_height})")
        print(f"  режим цвета: {mode}, альфа-канал: {'есть' if has_alpha else 'нет'}")

        problems = []
        if (width, height) != (expect_width, expect_height):
            problems.append(
                f"нужен размер {expect_width}x{expect_height} px, а не {width}x{height}"
            )
        if has_alpha and not alpha_allowed:
            problems.append("альфа-канал запрещён для этого изображения")
        if path.suffix.lower() not in (".png", ".jpg", ".jpeg"):
            problems.append("нужен PNG или JPEG")

        if problems:
            for problem in problems:
                print(f"  [ОШИБКА] {problem}")
            return 1

        print("  [OK] соответствует требованиям")
        return 0


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    exit_code = 0
    for argument in sys.argv[1:]:
        path = Path(argument)
        if not path.exists():
            print(f"[ОШИБКА] файл не найден: {path}")
            exit_code = 1
            continue
        if path.name not in REQUIREMENTS:
            # Всё, что лежит в папке скриншотов, проверяем по общим правилам
            # для снимков экрана.
            exit_code |= describe_screenshot(path)
            print()
            continue
        exit_code |= describe(path)
        print()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
