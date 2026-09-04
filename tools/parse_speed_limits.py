#!/usr/bin/env python3
"""
Разбирает "скорости все новые.xlsx" в структурированный список постоянных ограничений
скорости (участок км+пк -> км+пк => скорость для 3ЭС5К/ВЛ85) для нашего перегона
Хилок - Карымская - Чернышевск-Забайкальский.

Берёт из каждой строки только: диапазон км+пк (колонка "Станция/перегон") и основную
скорость для гружёного поезда (колонка "3ЭС5К ВЛ85" / "3ЭС5К"). Остальные колонки
(приёмо-отправочные пути и стрелки, обрывные места, проба тормозов, осмотр поезда,
скорость для порожних вагонов) в эту версию не идут — это отдельные функции ТЗ
(пиктограммы, оповещения), разберём их отдельно.

Формат строк неоднородный (данные набирались вручную с бумажных документов), поэтому
скрипт понимает основные варианты:
  - "Хилок (5932 пк 7 - 5935 пк 3)"        -> km явно с обеих сторон
  - "5935 пк 3 - 5942 пк 4"                -> то же, без названия станции
  - "6255 пк 9 - 6261 пк 2"                -> то же
  - "6095 пк 2 - 5"                        -> один км, диапазон пикетов
  - "6307 пк 4 - 7"                        -> то же
  - "6304 пк 8-10"                         -> то же, без пробелов вокруг дефиса
  - "5952 пк 10"                           -> одна точка (пикет без диапазона)
Строки без картинки км+пк (только "Путь 4", "стр. №37", "съезд 5-7" и т.п.) не
привязаны сами по себе к участку пути — они уточняют скорость для конкретного пути/
стрелки СТАНЦИИ, чей диапазон км+пк был объявлен в предыдущей "заголовочной" строке.
Такие строки попадают в отдельный список track_specific — они не нужны для основной
кривой скорости (которая считается по ходу главного пути), но их стоит сохранить.

Известные опечатки в исходном файле (руками вбито с бумаги) — Кириллические "З"/"б" вместо
цифр "3"/"6" внутри самого номера км или пикета (например "6124 пк З", "б376") —
нормализуются, но только внутри цифровых групп км+пк, а не по всему тексту (иначе
обычная буква "о" в названиях станций вроде "Хилок"/"Гонгота"/"Могзон" ошибочно
считалась бы опечаткой "0" — так и было в первой версии скрипта). Каждая такая правка
помечается в поле "note", чтобы её можно было перепроверить по бумажному документу.

Строки, которые не удалось разобрать ни одним из правил, идут в unparsed — их описание
нужно смотреть и разбирать вручную.
"""
import sys
import json
import re
import openpyxl

# Явные направления, которые нужны для перегона Хилок - Карымская - Чернышевск.
# "чётное" = пикеты растут (см. ТЗ раздел 3); growing=True/False задаёт это для рантайма.
SHEETS = {
    "Хилок-КРМ": {"direction": "even", "growing": True},
    "КРМ-Хилок": {"direction": "odd", "growing": False},
    "КРМ-Черн": {"direction": "even", "growing": True},
    "Черн-КРМ": {"direction": "odd", "growing": False},
}

CYRILLIC_DIGIT_FIXES = str.maketrans({"З": "3", "б": "6"})

# Digit group that also accepts the two lookalikes actually seen in the source data
# ("З" for "3", "б" for "6") right where a km or picket number is expected.
_D = r"[\dЗб]"

RANGE_BOTH_KM = re.compile(rf"({_D}{{4}})\s*пк\s*({_D}{{1,2}})\s*[-–]\s*({_D}{{4}})\s*пк\s*({_D}{{1,2}})")
RANGE_SAME_KM = re.compile(rf"({_D}{{4}})\s*пк\s*({_D}{{1,2}})\s*[-–]\s*({_D}{{1,2}})(?!\d)")
SINGLE_POINT = re.compile(rf"({_D}{{4}})\s*пк\s*({_D}{{1,2}})")


def to_int(group):
    fixed = group.translate(CYRILLIC_DIGIT_FIXES)
    return int(fixed), fixed != group


def parse_location(raw_text):
    """Returns (km_start, pk_start, km_end, pk_end, had_typo_fix) or None."""
    m = RANGE_BOTH_KM.search(raw_text)
    if m:
        values = [to_int(g) for g in m.groups()]
        km1, pk1, km2, pk2 = (v for v, _ in values)
        return km1, pk1, km2, pk2, any(fixed for _, fixed in values)

    m = RANGE_SAME_KM.search(raw_text)
    if m:
        values = [to_int(g) for g in m.groups()]
        km, pk1, pk2 = (v for v, _ in values)
        return km, pk1, km, pk2, any(fixed for _, fixed in values)

    m = SINGLE_POINT.search(raw_text)
    if m:
        values = [to_int(g) for g in m.groups()]
        km, pk = (v for v, _ in values)
        return km, pk, km, pk, any(fixed for _, fixed in values)

    return None


def is_usable_speed(value):
    return isinstance(value, (int, float))


def parse_sheet(ws, direction, growing):
    rows = []
    track_specific = []
    unparsed = []

    for row in ws.iter_rows(min_row=5, values_only=True):
        empty_wagons_speed = row[0]
        location_text = row[1]
        main_speed = row[2]

        if location_text is None:
            continue
        location_text = str(location_text).strip()
        if not location_text:
            continue

        parsed = parse_location(location_text)
        if parsed is None:
            track_specific.append({
                "text": location_text,
                "main_speed": main_speed if is_usable_speed(main_speed) else None,
            })
            continue

        km_start, pk_start, km_end, pk_end, had_typo_fix = parsed

        if not is_usable_speed(main_speed):
            unparsed.append({
                "text": location_text,
                "reason": f"main speed cell is not a plain number: {main_speed!r}",
            })
            continue

        entry = {
            "direction": direction,
            "growing": growing,
            "km_start": km_start,
            "pk_start": pk_start,
            "km_end": km_end,
            "pk_end": pk_end,
            "speed_kmh": main_speed,
            "source_text": location_text,
        }
        if is_usable_speed(empty_wagons_speed):
            entry["empty_wagons_speed_kmh"] = empty_wagons_speed
        if had_typo_fix:
            entry["note"] = "cyrillic-lookalike digits normalized, verify against paper source"
        rows.append(entry)

    return rows, track_specific, unparsed


def main():
    xlsx_path = sys.argv[1] if len(sys.argv) > 1 else "скорости все новые.xlsx"
    out_path = sys.argv[2] if len(sys.argv) > 2 else "speed_limits.json"

    wb = openpyxl.load_workbook(xlsx_path, data_only=True)

    all_rows = []
    all_track_specific = []
    all_unparsed = []

    for sheet_name, meta in SHEETS.items():
        ws = wb[sheet_name]
        rows, track_specific, unparsed = parse_sheet(ws, meta["direction"], meta["growing"])
        print(f"{sheet_name}: {len(rows)} parsed, {len(track_specific)} track-specific, "
              f"{len(unparsed)} unparsed")
        all_rows.extend(rows)
        for t in track_specific:
            t["sheet"] = sheet_name
        all_track_specific.extend(track_specific)
        for u in unparsed:
            u["sheet"] = sheet_name
        all_unparsed.extend(unparsed)

    # Only the clean, resolved ranges go into the app asset. track_specific/unparsed are
    # reported below for manual review, not shipped to the app.
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(all_rows, f, ensure_ascii=False, separators=(",", ":"))

    typo_fixes = [r for r in all_rows if "note" in r]
    print(f"\nTotal: {len(all_rows)} speed-limit rows, {len(all_track_specific)} "
          f"track-specific (not included), {len(all_unparsed)} unparsed (need review)")
    print(f"Wrote {out_path}")

    if typo_fixes:
        print(f"\n--- {len(typo_fixes)} rows with normalized digit typos (verify against paper) ---")
        for r in typo_fixes:
            print(f"  {r['source_text']!r} -> {r['km_start']} пк{r['pk_start']} - "
                  f"{r['km_end']} пк{r['pk_end']}")

    if all_unparsed:
        print(f"\n--- {len(all_unparsed)} unparsed rows (need review) ---")
        for u in all_unparsed:
            print(f"  [{u['sheet']}] {u['text']!r}: {u['reason']}")


if __name__ == "__main__":
    main()
