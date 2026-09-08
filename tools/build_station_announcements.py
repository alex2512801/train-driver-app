#!/usr/bin/env python3
"""
Строит app/src/main/assets/station_announcements.json — км+пк точки, за threshold_m метров
до которой играть «Внимание! Впереди станция!» (ТЗ раздел 6, "Предвходной станции").

Машинист объяснил термин: "предвходной" — это не входной сигнал станции (Ч/Н) и не сама
станция, а последний перегонный светофор ("точка") ПЕРЕД входным — тот самый т.NN. Номер
самой последней "точки" перед станцией фиксированный: **т.2 для чётного направления, т.1
для нечётного** (автоблокировка считает вниз к станции чётными/нечётными числами
соответственно, см. README шаг 4/12 — последняя перед входным всегда 2 либо 1). Проверено на
Гыршелуне (реальные данные track_signals.json): т.2 в 3100 м перед станцией на чётном, т.1
в 2700 м перед станцией на нечётном — в обоих случаях именно они, а не входной сигнал сам
по себе, ближайшая "точка" перед входным. threshold_m по умолчанию — 1500 (ТЗ).

Как и с сигналами (match_yellow_signals.py): "2"/"1" называются одинаково у КАЖДОЙ станции
маршрута, поэтому нужная станция определяется по ближайшей позиции — берётся ближайший
"2"/"1" в том же направлении/документе, что и сама станция, не дальше STATION_TOLERANCE_M.

ИСКЛЮЧЕНИЯ (ENTRANCE_OVERRIDES) — Лесная (чётное) и Яблоновая (нечётное): машинист объяснил,
что на этом перегоне вообще нет проходных светофоров автоблокировки (другая система, что-то
вроде "полуавтоматики" по его словам) — поэтому у станции нет "т.2"/"т.1" в принципе (это и
была причина, почему обычный поиск не находил ничего в разумных пределах — ближайший
попавшийся "2"/"1" на самом деле принадлежал совсем другой станции, за 10+ км). Вместо этого
здесь объявляем за 3000 м до самого ВХОДНОГО сигнала станции (Ч/Н, обычный путь, не Ч/НД).

НЕ РЕШЕНО (честно, по каждой станции своя причина — не натягивал совпадение силой):
  - Хилок (чётное, начало маршрута документа) — перед ней самой на этой странице ничего нет,
    т.2 просто не существует в покрытии Хилок-Крм.pdf (начинается прямо с Хилока).
  - Карымская/"Парк" (везде, оба направления, оба сегмента) и Шилка (частично) — крупные
    станции со сложными горловинами (та же причина, что в README шаг 14 для
    match_yellow_signals.py: маршрутные светофоры горловины не всегда попадают в разбор
    линейного профиля) — этот случай ждёт координат от машиниста отдельно, вместе с К1.
Итог — 76 станций из 85 (89%, было 74 до ENTRANCE_OVERRIDES), остальные не резолвятся,
оставлены без записи (не подставлено наугад).
"""
import json
import sys

STATION_TOLERANCE_M = 6000
ENTRANCE_OVERRIDE_TOLERANCE_M = 3000
DEFAULT_THRESHOLD_M = 1500

# (station_name, direction, source) -> (буква входного сигнала правильного пути, порог в метрах)
ENTRANCE_OVERRIDES = {
    ('Лесная', 'чётное', 'Хилок-Крм.pdf'): ('Ч', 3000),
    ('Яблоновая', 'нечётное', 'Крм-Хилок.pdf'): ('Н', 3000),
}


def chainage(km, pk):
    return km * 1000 + (pk - 1) * 100


def resolve_override(station, signals, entrance_name, threshold_m):
    candidates = [
        s for s in signals
        if s['direction'] == station['direction']
        and s['source'] == station['source']
        and s['name'] == entrance_name
    ]
    if not candidates:
        return None, 'override_not_found'

    station_chainage = chainage(station['km'], station['pk'])
    best = min(candidates, key=lambda s: abs(chainage(s['km'], s['pk']) - station_chainage))
    dist = abs(chainage(best['km'], best['pk']) - station_chainage)
    if dist > ENTRANCE_OVERRIDE_TOLERANCE_M:
        return None, 'override_too_far'

    return {
        'station': station['name'],
        'segment': station['segment'],
        'direction': station['direction'],
        'source': station['source'],
        'predvhodnoy_km': best['km'],
        'predvhodnoy_pk': best['pk'],
        'threshold_m': threshold_m,
    }, None


def main():
    signals_path = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/track_signals.json'
    stations_path = sys.argv[2] if len(sys.argv) > 2 else 'app/src/main/assets/track_stations.json'
    out_path = sys.argv[3] if len(sys.argv) > 3 else 'app/src/main/assets/station_announcements.json'

    with open(signals_path, encoding='utf-8') as f:
        signals = json.load(f)
    with open(stations_path, encoding='utf-8') as f:
        stations = json.load(f)

    results = []
    skipped = []
    for station in stations:
        override_key = (station['name'], station['direction'], station['source'])
        if override_key in ENTRANCE_OVERRIDES:
            entrance_name, threshold_m = ENTRANCE_OVERRIDES[override_key]
            result, reason = resolve_override(station, signals, entrance_name, threshold_m)
            if result is not None:
                results.append(result)
            else:
                skipped.append((station, reason))
            continue

        predvhodnoy_name = '2' if station['direction'] == 'чётное' else '1'
        candidates = [
            s for s in signals
            if s['direction'] == station['direction']
            and s['source'] == station['source']
            and s['name'] == predvhodnoy_name
        ]
        if not candidates:
            skipped.append((station, 'not_found'))
            continue

        station_chainage = chainage(station['km'], station['pk'])
        best = min(candidates, key=lambda s: abs(chainage(s['km'], s['pk']) - station_chainage))
        dist = abs(chainage(best['km'], best['pk']) - station_chainage)
        if dist > STATION_TOLERANCE_M:
            skipped.append((station, 'too_far'))
            continue

        results.append({
            'station': station['name'],
            'segment': station['segment'],
            'direction': station['direction'],
            'source': station['source'],
            'predvhodnoy_km': best['km'],
            'predvhodnoy_pk': best['pk'],
            'threshold_m': DEFAULT_THRESHOLD_M,
        })

    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, separators=(',', ':'))

    print(f'Resolved: {len(results)}/{len(stations)}')
    for station, reason in skipped:
        print(f'  skipped ({reason}): {station["name"]} {station["direction"]} {station["source"]}')
    print(f'Wrote {out_path}')


if __name__ == '__main__':
    main()
