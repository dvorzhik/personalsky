"""Снимает скриншоты приложения для страницы в Google Play.

Запуск: python tools/take-store-screenshots.py [-Serial <номер>] [-Out <каталог>]

Зачем: требования Google Play к скриншотам телефона — формат PNG или JPEG,
минимум 320 пикселей по короткой стороне и не более 3840 по длинной,
от 2 до 8 штук. Снимок экрана с телефона уже подходит по сторонам, но
его нужно проверить и разложить по понятным именам, чтобы не перепутать
кадры при загрузке в Play Console. Скрипт делает и то, и другое.
"""
import argparse
import hashlib
import re
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image

PACKAGE = "com.personalsky.app"

# Даже при скрытой панели Android рисует поверх значок заряда. Google Play
# не рекомендует показывать системные индикаторы на скриншотах, поэтому
# правая часть верхней полосы затирается фоном приложения.
STATUS_BAR_HEIGHT = 120
STATUS_BAR_RIGHT_ZONE = 280


def clean_status_bar(path: Path) -> None:
    with Image.open(path) as image:
        pixels = image.load()
        # Цвет фона берём ниже индикатора, чтобы не угадывать его значение.
        background = pixels[image.width - 10, STATUS_BAR_HEIGHT + 40]
        for y in range(STATUS_BAR_HEIGHT):
            for x in range(image.width - STATUS_BAR_RIGHT_ZONE, image.width):
                pixels[x, y] = background
        image.save(path)

# Порядок кадров и переходы между ними. Каждый шаг — подпись кнопки,
# по которой нажимаем, чтобы попасть на следующий экран, и признак того,
# что перед нажатием экран нужно вернуть в исходное положение: на истории
# созвездия кнопки архива находятся ниже сгиба, а после нажатия на них
# кнопка возврата тоже остаётся внизу.
#
# Отрицательное значение — прокрутить вверх, положительное — вниз,
# ноль — не прокручивать.
NAVIGATION = [
    ("01-goal-settings", "Управлять целью", 0),
    ("02-active-sky", "К карте", 0),
    ("03-history", "История созвездия", 0),
    ("04-archive", "Архив небосвода", 1),
    ("05-support", "Поддержать проект", 0),
]


def find_adb() -> str:
    candidate = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
    return str(candidate) if candidate.exists() else "adb"


def shell(adb: str, serial: str | None, *arguments: str) -> str:
    command = [adb]
    if serial:
        command += ["-s", serial]
    command += ["shell", *arguments]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    return ((result.stdout or "") + (result.stderr or "")).strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-Serial", dest="serial", default=None)
    parser.add_argument("-Out", dest="output", default="store-screenshots")
    arguments = parser.parse_args()

    adb = find_adb()
    output = Path(arguments.output)
    output.mkdir(exist_ok=True)

    print("Готовлю экран: убираю часы, батарею и значки сети...")
    # Google Play не рекомендует показывать в статусбаре посторонний контент,
    # поэтому панель скрывается на время съёмки. Настройка применяется
    # к следующему запуску приложения, поэтому сначала перезапускаем его,
    # а снимки делаем уже без панели.
    shell(adb, arguments.serial, "settings", "put", "global", "policy_control",
          "immersive.full=*")
    time.sleep(2)

    print("Открываю приложение...")
    shell(adb, arguments.serial, "am", "force-stop", PACKAGE)
    time.sleep(2)
    shell(adb, arguments.serial, "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    time.sleep(8)

    # Проверяем, что панель действительно скрыта: если настройка не легла,
    # на всех кадрах останутся часы и проценты заряда.
    hierarchy = shell(adb, arguments.serial, "uiautomator", "dump", "/sdcard/probe.xml")
    shell(adb, arguments.serial, "pull", "/sdcard/probe.xml", str(output / "probe.xml"))
    probe = output / "probe.xml"
    if probe.exists():
        text = probe.read_text(encoding="utf-8", errors="ignore")
        # Часы статусбара видны как короткий текст вида «17:53» в верхней зоне.
        clock = re.search(r'text="(\d{1,2}:\d{2})"', text)
        if clock:
            print(f"  [ВНИМАНИЕ] в статусбаре остались часы: {clock.group(1)}")
        else:
            print("  [OK] статусбар скрыт")
        probe.unlink()

    taken: dict[str, Path] = {}
    for name, tap_text, scroll_direction in NAVIGATION:
        if scroll_direction:
            # uiautomator видит только то, что попало на экран, поэтому
            # перед нажатием возвращаем нужный участок в видимую область.
            if scroll_direction > 0:
                shell(adb, arguments.serial, "input", "swipe",
                      "540", "1800", "540", "800", "300")
            else:
                shell(adb, arguments.serial, "input", "swipe",
                      "540", "800", "540", "1800", "300")
            time.sleep(2)
        if tap_text:
            result = subprocess.run(
                [
                    "powershell", "-ExecutionPolicy", "Bypass", "-File",
                    "tools/tap-by-text.ps1", "-Text", tap_text,
                    *(["-Serial", arguments.serial] if arguments.serial else []),
                ],
                capture_output=True, text=True, encoding="utf-8",
            )
            # Скрипт нажатия печатает «не найден», если элемента нет:
            # без этой проверки кадр молча повторял бы предыдущий экран.
            combined = (result.stdout or "") + (result.stderr or "")
            if "не найден" in combined:
                print(f"  [ОШИБКА] перед снимком {name} не найдена кнопка «{tap_text}»")
                return 1
            time.sleep(3)

        remote = f"/sdcard/{name}.png"
        shell(adb, arguments.serial, "screencap", "-p", remote)
        target = output / f"{name}.png"
        subprocess.run(
            [adb, *(["-s", arguments.serial] if arguments.serial else []),
             "pull", remote, str(target)],
            capture_output=True,
        )
        if not target.exists():
            print(f"  [ОШИБКА] не удалось снять {name}")
            continue
        clean_status_bar(target)
        with Image.open(target) as image:
            width, height = image.size
        fits = min(width, height) >= 320 and max(width, height) <= 3840
        mark = "[OK]" if fits else "[ОШИБКА] размер вне требований"
        print(f"  {mark} {target.name}: {width}x{height}")
        taken[name] = target

    shell(adb, arguments.serial, "settings", "put", "global", "policy_control", "null")
    print("Статусбар возвращён на место.")

    # Одинаковые кадры означают, что навигация не сработала: так было,
    # когда первый кадр попал в листинг дважды с разными именами.
    hashes: dict[str, str] = {}
    for name, path in taken.items():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
        hashes.setdefault(digest, name)
    duplicates = len(taken) - len(hashes)
    if duplicates:
        print(f"\n[ОШИБКА] одинаковых кадров: {duplicates} — проверьте подписи переходов")
        return 1

    files = sorted(output.glob("*.png"))
    print(f"\nСнято файлов: {len(files)} (Google Play принимает от 2 до 8)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
