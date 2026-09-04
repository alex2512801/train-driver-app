#!/usr/bin/env python3
"""
Разбирает "Пробы_тормозов.xlsx" в список точек пробы тормозов для перегона
Хилок - Карымская - Чернышевск-Забайкальский (ТЗ, раздел 6 — голосовое оповещение
"Впереди проба тормозов!" за 2000 м до точки, для сдвоенного поезда — отдельным текстом).

Берутся только листы "Хилок-КРМ" и "КРМ-Черныш" — они уже вырезка из полного списка проб
по всей дороге (листы "Общие"/"Для всех"/"КРМ-Оловян"/"ПЗВ-Хилок" — другие участки, не нужны
для этого приложения).

ВАЖНО (со слов машиниста): км+пк в этом списке — это координата САМОЙ пробы тормозов,
объявлять нужно за 2000 м до неё. Если режимная карта (профиль пути в PDF) показывает пробу
тормозов в другом месте — правильные данные здесь, в этом списке, а не на карте.

Формат листов: чередующиеся блоки "Четное"/"Нечетное" (заголовок направления), затем строка
с именами колонок, затем сами строки участков. Строки с суффиксом "(сдвоен.)" в названии
перегона — это отдельная, другая точка пробы для сдвоенного (двойного) поезда, а не то же
место с другим текстом объявления. Как машинист будет отмечать в приложении, что поезд
сдвоенный, ещё не решено — здесь просто сохраняется признак double_train.

Скорость вида "55/60" означает диапазон скорости, в котором проба тормозов засчитывается
(55-60 км/ч); проба вне этого диапазона недействительна, нужно ждать следующую точку. Если
диапазонов несколько (например "45/50 55/60 65/70"), подходит любой из них.
"""
import sys
import re
import json
import openpyxl

SHEETS = ["Хилок-КРМ", "КРМ-Черныш"]
DIRECTION_MAP = {"Четное": "even", "Нечетное": "odd"}
SPEED_RANGE = re.compile(r"(\d+)\s*/\s*(\d+)")

DOUBLE_TRAIN_SUFFIX = re.compile(r"\s*\(сдвоен\.?\)\s*$", re.IGNORECASE)


def parse_sheet(ws):
    entries = []
    direction = None
    for row in ws.iter_rows(min_row=1, values_only=True):
        # row layout: (None, peregon_or_marker, km, pk, speed)
        marker = row[1]
        if marker in DIRECTION_MAP:
            direction = DIRECTION_MAP[marker]
            continue
        if marker in (None, "Перегон"):
            continue

        peregon, km, pk, speed_text = row[1], row[2], row[3], row[4]
        if km is None or pk is None:
            continue

        double_train = bool(DOUBLE_TRAIN_SUFFIX.search(peregon))
        peregon_clean = DOUBLE_TRAIN_SUFFIX.sub("", peregon).strip()

        ranges = [[int(a), int(b)] for a, b in SPEED_RANGE.findall(str(speed_text))]

        entries.append({
            "direction": direction,
            "peregon": peregon_clean,
            "double_train": double_train,
            "km": km,
            "pk": pk,
            "valid_speed_ranges_kmh": ranges,
        })
    return entries


def main():
    xlsx_path = sys.argv[1] if len(sys.argv) > 1 else "Пробы_тормозов.xlsx"
    out_path = sys.argv[2] if len(sys.argv) > 2 else "brake_tests.json"

    wb = openpyxl.load_workbook(xlsx_path, data_only=True)

    all_entries = []
    for sheet_name in SHEETS:
        entries = parse_sheet(wb[sheet_name])
        print(f"{sheet_name}: {len(entries)} entries")
        all_entries.extend(entries)

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(all_entries, f, ensure_ascii=False, separators=(",", ":"))

    double_count = sum(1 for e in all_entries if e["double_train"])
    print(f"\nTotal: {len(all_entries)} entries ({double_count} для сдвоенного поезда)")
    print(f"Wrote {out_path}")


if __name__ == "__main__":
    main()
