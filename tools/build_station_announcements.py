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

КАРЫМСКАЯ (KARYMSKAYA_ARRIVALS) — машинист объяснил: физически это одна станция, но состоит
из двух "парков" — Парк Д с одной стороны (со стороны Хилка) и Парк Г с другой (со стороны
Чернышевска). "Карымская" как отдельная точка не объявляется вообще — объявление идёт перед
ОДНИМ из парков, тем, через который прибываешь: т.2 перед Парком Д (чётное, Хилок-Крм.pdf) и
т.1 перед Парком Г (нечётное, Чернышевск-Крм.pdf). Два других направление×сегмент для
Карымской (нечётное Крм-Хилок.pdf, чётное Крм-Чернышевск.pdf) — это ОТПРАВЛЕНИЕ от Карымской
(документ начинается прямо на ней), там объявлять нечего, как и с Хилоком.
`track_stations.json` даёт для каждого из двух прибывающих направлений ДВЕ строки "Парк" —
это оба парка, случайно попавшие на одну и ту же страницу профиля (без буквы Д/Г, извлечение
их не различает); по объяснению машиниста берётся только ближняя (та, что встречается первой
по ходу движения) — она и есть парк со стороны прибытия, вторая просто отбрасывается, чтобы
не объявлять дважды.

НЕ РЕШЕНО (честно, по каждой причина своя — не натягивал совпадение силой):
  - Хилок (чётное, начало маршрута документа) — перед ней самой на этой странице ничего нет,
    т.2 просто не существует в покрытии Хилок-Крм.pdf (начинается прямо с Хилока).
  - Шилка (частично, 1 из 6 строк) — та же причина, что и раньше у Карымской (сложная
    горловина), но лишь один случай, не разбирался отдельно.
Итог — 73 объявления из 85 строк track_stations.json (86%; знаменатель включает дублирующиеся
строки "Парк"/"Карымская", каждая из которых теперь даёт максимум одно объявление на весь
комплекс вместо нескольких — см. KARYMSKAYA_ARRIVALS), остальные не резолвятся, оставлены без
записи (не подставлено наугад).
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

# Имена, которые относятся к комплексу "Карымская" (сама станция + оба парка) — исключаются
# из обычного цикла по track_stations.json целиком и обрабатываются отдельно, см. ниже.
KARYMSKAYA_NAMES = {'Карымская', 'Парк'}

# (direction, source) прибытия -> ближний парк ищем по этому источнику имени (та строка "Парк",
# что встречается ПЕРВОЙ по ходу движения: наименьший км для чётного, наибольший для нечётного
# — см. docstring выше), а т.2/т.1 — обычным способом от найденной точки парка.
KARYMSKAYA_ARRIVALS = {
    ('чётное', 'Хилок-Крм.pdf'): {'pick': 'min', 'predvhodnoy_name': '2'},
    ('нечётное', 'Чернышевск-Крм.pdf'): {'pick': 'max', 'predvhodnoy_name': '1'},
}


def chainage(km, pk):
    return km * 1000 + (pk - 1) * 100


def find_nearest_named(direction, source, target_chainage, name, signals, tolerance_m):
    candidates = [s for s in signals if s['direction'] == direction and s['source'] == source and s['name'] == name]
    if not candidates:
        return None, 'not_found'
    best = min(candidates, key=lambda s: abs(chainage(s['km'], s['pk']) - target_chainage))
    dist = abs(chainage(best['km'], best['pk']) - target_chainage)
    if dist > tolerance_m:
        return None, 'too_far'
    return best, None


def resolve_override(station, signals, entrance_name, threshold_m):
    station_chainage = chainage(station['km'], station['pk'])
    best, reason = find_nearest_named(
        station['direction'], station['source'], station_chainage, entrance_name, signals,
        ENTRANCE_OVERRIDE_TOLERANCE_M
    )
    if best is None:
        return None, f'override_{reason}'

    return {
        'station': station['name'],
        'segment': station['segment'],
        'direction': station['direction'],
        'source': station['source'],
        'predvhodnoy_km': best['km'],
        'predvhodnoy_pk': best['pk'],
        'threshold_m': threshold_m,
    }, None


def resolve_karymskaya_arrivals(stations, signals):
    """Строит записи для двух парков Карымской (см. docstring модуля, KARYMSKAYA_ARRIVALS)."""
    results = []
    skipped = []
    for (direction, source), cfg in KARYMSKAYA_ARRIVALS.items():
        park_rows = [
            s for s in stations
            if s['name'] == 'Парк' and s['direction'] == direction and s['source'] == source
        ]
        if not park_rows:
            skipped.append(({'name': 'Карымская', 'direction': direction, 'source': source}, 'park_not_found'))
            continue

        picker = min if cfg['pick'] == 'min' else max
        park = picker(park_rows, key=lambda s: chainage(s['km'], s['pk']))
        park_chainage = chainage(park['km'], park['pk'])

        best, reason = find_nearest_named(
            direction, source, park_chainage, cfg['predvhodnoy_name'], signals, STATION_TOLERANCE_M
        )
        if best is None:
            skipped.append((park, f'karymskaya_{reason}'))
            continue

        results.append({
            'station': 'Карымская',
            'segment': park['segment'],
            'direction': direction,
            'source': source,
            'predvhodnoy_km': best['km'],
            'predvhodnoy_pk': best['pk'],
            'threshold_m': DEFAULT_THRESHOLD_M,
        })
    return results, skipped


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

    karymskaya_results, karymskaya_skipped = resolve_karymskaya_arrivals(stations, signals)
    results.extend(karymskaya_results)
    skipped.extend(karymskaya_skipped)

    for station in stations:
        if station['name'] in KARYMSKAYA_NAMES:
            continue  # обработано отдельно выше (resolve_karymskaya_arrivals)

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
