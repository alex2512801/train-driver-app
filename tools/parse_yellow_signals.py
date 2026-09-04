#!/usr/bin/env python3
"""
Разбирает "сигналы_желтый_чётное.xlsx" и "сигналы_желтый_нечётное.xlsx" в единый список
условных предупреждений о жёлтом сигнале (ТЗ, раздел 6 / УСАВП-аналог_ИТОГ.pdf раздел
"Условное предупреждение про жёлтый сигнал").

Логика функции (не реализуется этим скриптом, только данные для неё): при вступлении
поезда на блок-участок перед сигналом "До" — то есть сразу после проследования сигнала
"От" — если этот сигнал "До" показывает жёлтый, машинисту заранее объявляется
"Если впереди сигнал [До] с жёлтым показанием, то скорость проследования не более
[yellow_speed_kmh] км/ч". Приложение не знает фактический цвет сигнала (нет связи с СЦБ) —
это только напоминание правила.

В отличие от файла со скоростями, эти два размечены аккуратно (фиксированные колонки,
без опечаток) — обычное чтение таблицы, без regex-разбора текста.

ВАЖНО (не решено этим скриптом): здесь нет км+пк расположения сигналов "От"/"До" — только
их литеры и названия станций/перегонов. Чтобы функция сработала по факту вступления на
блок-участок, нужно ещё сопоставить каждый сигнал "От" с его пикетом (см. профили пути в
PDF или список сигналов на светофоры). Эта привязка сигнал -> км+пк пока не сделана.
"""
import sys
import json
import openpyxl

FILES = {
    "even": ("сигналы_желтый_чётное.xlsx", "Чётное направление"),
    "odd": ("сигналы_желтый_нечётное.xlsx", "Нечётное направление"),
}


def parse_file(path, sheet_name, direction):
    wb = openpyxl.load_workbook(path, data_only=True)
    ws = wb[sheet_name]

    entries = []
    issues = []
    for i, row in enumerate(ws.iter_rows(min_row=2, values_only=True), start=2):
        location, from_signal, to_signal, length_m, yellow_speed, wrong_path = row
        if location is None and from_signal is None:
            continue

        missing = [
            name for name, value in [
                ("Место установки", location),
                ("От (сигнал)", from_signal),
                ("До (сигнал)", to_signal),
                ("Длина участка, м", length_m),
                ("Скорость при жёлтом, км/ч", yellow_speed),
            ]
            if value is None
        ]
        if missing:
            issues.append({"row": i, "missing": missing, "raw": row})
            continue

        entries.append({
            "direction": direction,
            "location": str(location).strip(),
            "from_signal": str(from_signal).strip(),
            "to_signal": str(to_signal).strip(),
            "block_length_m": length_m,
            "yellow_speed_kmh": yellow_speed,
            "wrong_path": str(wrong_path).strip().lower() == "да" if wrong_path else False,
        })
    return entries, issues


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "yellow_signal_warnings.json"
    src_dir = sys.argv[2] if len(sys.argv) > 2 else "."

    all_entries = []
    for direction, (fname, sheet) in FILES.items():
        entries, issues = parse_file(f"{src_dir}/{fname}", sheet, direction)
        print(f"{fname}: {len(entries)} parsed, {len(issues)} with missing fields")
        for issue in issues:
            print(f"  row {issue['row']}: missing {issue['missing']} -- {issue['raw']}")
        all_entries.extend(entries)

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(all_entries, f, ensure_ascii=False, separators=(",", ":"))

    wrong_path_count = sum(1 for e in all_entries if e["wrong_path"])
    print(f"\nTotal: {len(all_entries)} entries ({wrong_path_count} по неправильному пути)")
    print(f"Wrote {out_path}")


if __name__ == "__main__":
    main()
