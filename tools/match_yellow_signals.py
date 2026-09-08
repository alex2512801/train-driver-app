#!/usr/bin/env python3
"""
Второй шаг после `parse_yellow_signals.py`: пытается проставить км+пк сигналам "От"/"До"
в уже собранном `yellow_signal_warnings.json`, используя `track_signals.json` и
`track_stations.json` (шаг 12-13 README, `parse_track_profile.py`). Запускать ПОСЛЕ
parse_yellow_signals.py — тот перезаписывает файл целиком с нуля (без км+пк), этот
дописывает км+пк туда же поверх уже готовых записей.

Почему это не тривиальный JOIN по имени литеры:
  - Входные светофоры называются буквально одинаково ("Ч"/"Н") на КАЖДОЙ станции всего
    участка — у "Ч" в направлении "чётное" оказывается по 30+ одноимённых кандидатов в
    track_signals.json. Различить их можно только по станции: берём station location из
    самой записи yellow_signal_warnings (или из суффикса "ст. XXX", если он есть прямо в
    названии сигнала — см. STATION_SUFFIX_RE) и ищем её км в track_stations.json, затем
    берём ближайшего по км кандидата с тем же именем (и требуем, чтобы он был в пределах
    STATION_TOLERANCE_M — иначе это не тот сигнал, а совпадение имени за много км).
  - "т.11"/"т.14" (машинист называет так голые номера автоблокировки) — здесь это просто
    "11"/"14" в терминах track_signals.json (см. README шаг 4 "термины сигналов").
  - Реальные опечатки/путаница похожих букв в исходном xlsx: Cyrillic "З" вместо цифры
    "3" (например "ЧМЗ" — на самом деле "ЧМ3", маршрутный светофор так не называется
    буквой "Ч"+"М"+"З"), латинская "H" вместо кириллической "Н" в одной записи ("H1").
    NORMALIZE_MAP чинит эти случаи явно — это не общая транслитерация, а конкретно
    наблюдаемые в данных ошибки ввода, только внутри поля с литерой сигнала (не трогает
    названия станций, где кириллица настоящая).
  - Записи вида "ЧМ32 - ЧМ37" или "Ч51 - Ч55" — это диапазон (несколько светофоров одной
    горловины), а не один сигнал; км+пк одной точки для них не определить без потери
    смысла — такие явно помечаются unresolved:range, а не додумываются до одной точки.
  - "вх. ЧА ст. Чита-2" — это подсказка САМОГО источника, что сигнал относится к другой
    станции, чем location записи (пограничный случай двух смежных станций) — извлекается
    STATION_SUFFIX_RE и используется вместо location.

ЧЕСТНО НЕ РЕШЕНО (важно, посмотри resolution перед тем как доверять числу):
  - Профили пути, которые есть у нас, покрывают только Хилок — Карымская —
    Чернышевск-Забайкальский. Все записи yellow_signal_warnings для станций дальше
    (Чита-1/2, Антипиха, Маккавеево, Дарасун, Могзон, Туринская, Шилка-товарная и т.д. —
    это уже участок Карымская — Чита — Шилка, для которого таких PDF-профилей нам не
    присылали) помечаются unresolved:out_of_scope и НЕ пытаются резолвиться — попытка
    угадать по случайному совпадению имени в другом сегменте была бы хуже, чем честно
    ничего не найти.
  - Станционные горловины Карымской и Чернышевска-Забайкальского почти не резолвятся,
    даже когда станция в зоне покрытия: сложные маршрутные сигналы горловины (ЧМ32,
    ЧГ1, НМ10Б и т.п.) в наших профилях либо не встречаются вовсе (видимо, эти путевые
    развязки нарисованы в PDF отдельными, более подробными станционными схемами, а не на
    линейном профиле перегона — тех схем у нас нет), либо не входят в SIG_PATTERN. Это
    видно по низкому resolution rate для Карымской/Чернышевска в статистике на выходе.
"""
import sys
import json
import re
from collections import defaultdict

STATION_TOLERANCE_M = 5000

# Конкретно наблюдаемые в исходном xlsx опечатки/похожие буквы — не общая транслитерация.
NORMALIZE_MAP = str.maketrans({'З': '3', 'H': 'Н', 'A': 'А', 'B': 'В', 'C': 'С', 'E': 'Е',
                                'K': 'К', 'M': 'М', 'O': 'О', 'P': 'Р', 'T': 'Т', 'X': 'Х'})

STATION_SUFFIX_RE = re.compile(r'^вх\.\s*(\S+)\s+ст\.\s*(.+)$')
TOCHKA_RE = re.compile(r'^т\.(\d{1,2})$')
RANGE_RE = re.compile(r'\s*-\s*')

# location в yellow_signal_warnings.json -> имя станции в track_stations.json (то, что
# отличается только оформлением: "(парк Д)"/полное офиц. имя вместо короткого на схеме).
LOCATION_TO_STATION = {
    'Хилок': 'Хилок',
    'Карымская': 'Карымская',
    'Карымская (парк Д)': 'Карымская',
    'Чернышевск-Забайкальский': 'Чернышевск',
    'Яблоновая': 'Яблоновая',
    'Тургутуй': 'Тургутуй',
}

DIR_MAP = {'even': 'чётное', 'odd': 'нечётное'}


def normalize_signal_name(name):
    return name.translate(NORMALIZE_MAP)


def chainage(km, pk):
    return km * 1000 + (pk - 1) * 100


def build_indices(track_signals, track_stations):
    signals_idx = defaultdict(list)
    for r in track_signals:
        signals_idx[(r['direction'], r['name'])].append(r)

    stations_idx = defaultdict(list)
    for r in track_stations:
        stations_idx[(r['direction'], r['name'])].append(r)

    return signals_idx, stations_idx


def resolve_one(raw_name, direction_key, location, signals_idx, stations_idx):
    """Возвращает (km, pk, resolution) или (None, None, 'unresolved:<reason>')."""
    name = raw_name.strip()

    station_override = STATION_SUFFIX_RE.match(name)
    if station_override:
        name = station_override.group(1)
        location = station_override.group(2).strip()
        if location.startswith('ст. '):
            location = location[4:].strip()

    m = TOCHKA_RE.match(name)
    if m:
        name = m.group(1)

    if RANGE_RE.search(name) and not STATION_SUFFIX_RE.match(raw_name):
        return None, None, 'unresolved:range'

    name = normalize_signal_name(name)

    station_name = LOCATION_TO_STATION.get(location)
    if station_name is None:
        return None, None, 'unresolved:out_of_scope'

    candidates = signals_idx.get((direction_key, name), [])
    if not candidates:
        return None, None, 'unresolved:not_found'

    if len(candidates) == 1:
        c = candidates[0]
        return c['km'], c['pk'], 'unique'

    station_rows = stations_idx.get((direction_key, station_name), [])
    if not station_rows:
        return None, None, 'unresolved:ambiguous_no_station'

    station_chainage = sum(chainage(s['km'], s['pk']) for s in station_rows) / len(station_rows)
    best = min(candidates, key=lambda c: abs(chainage(c['km'], c['pk']) - station_chainage))
    dist = abs(chainage(best['km'], best['pk']) - station_chainage)
    if dist > STATION_TOLERANCE_M:
        return None, None, 'unresolved:ambiguous_too_far'
    return best['km'], best['pk'], 'nearest_station'


def main():
    yellow_path = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/yellow_signal_warnings.json'
    signals_path = sys.argv[2] if len(sys.argv) > 2 else 'app/src/main/assets/track_signals.json'
    stations_path = sys.argv[3] if len(sys.argv) > 3 else 'app/src/main/assets/track_stations.json'

    with open(yellow_path, encoding='utf-8') as f:
        yellow = json.load(f)
    with open(signals_path, encoding='utf-8') as f:
        track_signals = json.load(f)
    with open(stations_path, encoding='utf-8') as f:
        track_stations = json.load(f)

    signals_idx, stations_idx = build_indices(track_signals, track_stations)

    resolution_counts = defaultdict(int)
    for entry in yellow:
        direction_key = DIR_MAP[entry['direction']]
        for prefix, field in (('from', 'from_signal'), ('to', 'to_signal')):
            km, pk, resolution = resolve_one(
                entry[field], direction_key, entry['location'], signals_idx, stations_idx,
            )
            entry[f'{prefix}_km'] = km
            entry[f'{prefix}_pk'] = pk
            entry[f'{prefix}_resolution'] = resolution
            resolution_counts[resolution] += 1

    with open(yellow_path, 'w', encoding='utf-8') as f:
        json.dump(yellow, f, ensure_ascii=False, separators=(',', ':'))

    total = len(yellow) * 2
    resolved = sum(n for r, n in resolution_counts.items() if r in ('unique', 'nearest_station'))
    print(f'{len(yellow)} warning entries, {total} signal fields to resolve:')
    for r, n in sorted(resolution_counts.items(), key=lambda kv: -kv[1]):
        print(f'  {r}: {n}')
    print(f'\nResolved: {resolved}/{total} ({100 * resolved / total:.0f}%)')
    print(f'Wrote {yellow_path}')


if __name__ == '__main__':
    main()
