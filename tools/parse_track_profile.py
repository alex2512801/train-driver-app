#!/usr/bin/env python3
"""
Извлекает литеры и км+пк-положения светофоров, А ТАКЖЕ названия и км+пк станций из
графических профилей пути — 4 PDF-документа, присланных машинистом: "Хилок-Крм.pdf",
"Крм-Хилок.pdf", "Крм-Чернышевск.pdf", "Чернышевск-Крм.pdf" (в репозиторий не кладём —
большие файлы; положи их рядом со скриптом или передай путь к папке первым аргументом).
См. ТЗ раздел 1 ("Реальный профиль пути (высоты, сигналы, пикеты) в PDF") и раздел 4
(грамматика литер светофоров).

Названия станций нужны отдельно от сигналов — привязка сигнала к станции (см.
`match_yellow_signals.py`) для голых литер "Ч"/"Н" (входной светофор — они одинаково
называются на КАЖДОЙ станции, поэтому по одной литере не определить, к какой станции
относится конкретная запись без знания её приблизительного км).

До этого шага km+пк светофоров не было НИГДЕ в присланных данных — ни в
режимных картах, ни в файлах жёлтых сигналов (см. `parse_yellow_signals.py`,
раздел "не хватает привязки сигналов к км+пк" в README, шаг 4). Единственное
место, где сигнал нарисован рядом с km+пк-линейкой — это как раз эти профили.

Как это устроено в исходном PDF (на каждой странице — полоса ~14-15 км пути):
  - жирный "километровый" ряд (чёрные плашки с 4-значным км),
  - над и под ним — мелкие цифры 1..10 (номер пикета внутри км, пикет = 100 м),
  - иконки светофоров (текст-литера рядом с иконкой) выше линии рельефа,
  - красная зубчатая строка под км-рядом — значения уклона (здесь не используется).

Метод: по x-координате жирных км-плашек строится сетка "км", по x-координате
мелких цифр 1..10 рядом с этой же строкой — сетка "пикет". Для каждого
текста-кандидата на литеру светофора (см. SIG_PATTERN) берётся его x-центр и
находится ближайший пикет в этой сетке — это и даёт км+пк светофора.
Проверено вручную по картинке на первой странице "Хилок-Крм.pdf": все 15
найденных светофоров совпали и по литере, и по км+пк (отклонение x-центра
текста от центра пикета — не больше 1.1pt, то есть текст в исходнике рисуется
строго по центру своего пикета, привязка не приблизительная).

Литеры светофоров (грамматика ТЗ раздел 4): Ч/Н — входной; ЧА — вход. Чита-2
(особый случай); ЧМ2А и т.п. — маршрутный; Ч7 и т.п. — выходной; голые числа —
автоблокировка (т.NN у машиниста); ЧД/НД — предвходной на неправильном пути.
В самих PDF грамматика чуть шире задокументированной в ТЗ (встречаются ЧС,
Ч2-4, Н3з, НН — не описаны в ТЗ явно, но по контексту это тоже настоящие
литеры), поэтому SIG_PATTERN широкий (буква Ч/Н + до 5 букв/цифр/дефис), а не
точное перечисление форм.

Каждому найденному светофору проставлено direction — "чётное"/"нечётное" по
тому же соглашению, что уже используется в макете (Хилок→Карымская и
Карымская→Чернышевск — чётное; обратно — нечётное) — соответствует имени
исходного PDF-файла.

РАЗОБРАНО (уточнение от машиниста): на странице Тургутуй — Яблоновая (перегон с
третьим, "средним" путём — по нему поезда могут идти в обоих направлениях, в
отличие от обычного двухпутного перегона) одна и та же литера/пикет рисуется
дважды: один раз обычным синим кружком (путь по направлению документа), второй
раз красным кружком с подписью "средний" под ним — это НЕ дубль одного и того
же светофора, а действительно два разных физических светофора на двух разных
путях, которые просто совпали по номеру. Обнаруживается по тексту "средний",
который в исходнике нарисован сразу под номером светофора, почти впритык —
такие записи помечаются `"track": "средний"` и не схлопываются с одноимённым
сигналом основного пути.

Также уточнено: расхождение ~17% между двумя документами одного перегона
(Хилок-Крм.pdf и Крм-Хилок.pdf показывают один участок с двух концов) — это
**не ошибка склейки**, а ожидаемое поведение: станционные входные/выходные
светофоры (голая литера Ч/Н, буква+число без "М") рисуются только в том
документе, чьё направление движения через них реально проходит (например,
выходной "Н1" нужен только поезду, уходящему в нечётном направлении, — на
странице встречного документа его попросту нет, потому что он там не нужен).
Голые числа (автоблокировка) и предвходные ЧД/НД, которые актуальны для обоих
направлений на двухпутном перегоне, действительно совпадают в обоих
документах почти всегда. Поэтому оба документа сохраняются в выходном JSON
как есть (`source`/`direction` у каждой записи) — это не два конфликтующих
варианта одной истины, а взаимно дополняющие наборы данных.

Названия станций (extract_stations_from_page/pdf) находятся тем же способом, что и
сигналы (та же сетка км+пикет из build_picket_map), но по другому текстовому признаку:
название станции набрано тем же жирным шрифтом и той же высотой (~21.6pt), что и сами
км-плашки, только буквами (STATION_PATTERN), а не цифрами — и специально исключено из
сигналов диапазоном высоты (сигналы: <20pt, станции: 18-25pt, крупнее — уже другие
надписи вроде "Остановка"/"Опробование [тормозов]", 35-46pt, тоже отсечены).

НЕ РЕШЕНО ЭТИМ СКРИПТОМ (важно):
  - "НТ" — это не светофор, а отметка "опробование тормозов" (красная
    пунктирная линия с подписью вида "40/1000"), исключена явно по тексту.
  - Уклон (зубчатая красная строка) и профиль высоты (чёрная волнистая линия)
    не извлекаются вообще — только текст, координатной привязки к линии
    рельефа этот подход не даёт. Отдельная, более сложная задача на будущее.
  - Средний путь встречается только на одной странице в каждом из двух
    документов Хилок-Крм/Крм-Хилок (перегон Тургутуй — Яблоновая) — если он
    также есть где-то на участке Карымская — Чернышевск-Забайкальский под
    другим названием станций, этот скрипт его не найдёт по имени станции
    (детектор "средний" привязан только к тексту подписи, не к станции, так
    что он сработает где угодно — но стоит перепроверить визуально, если
    появятся новые PDF с похожей структурой).
  - У Карымской и Чернышевска-Забайкальского несколько подписей "Парк"/"Шилка"
    на разных х-позициях (станционные парки/подъезды) — сохранены как отдельные
    станции с тем же именем "Парк" на разном км; расшифровка, какой это парк,
    не сделана (в графике это не подписано отдельным текстом).
"""
import re
import json
import sys
import bisect

import fitz

SIG_PATTERN = re.compile(r'^[ЧН][A-Za-zА-Яа-я0-9-]{0,5}$|^\d{1,2}$')
NON_SIGNAL_EXACT = {'НТ'}

# (имя файла, направление, сегмент)
SOURCE_FILES = [
    ("Хилок-Крм.pdf", "чётное", "Хилок — Карымская"),
    ("Крм-Хилок.pdf", "нечётное", "Хилок — Карымская"),
    ("Крм-Чернышевск.pdf", "чётное", "Карымская — Чернышевск-Забайкальский"),
    ("Чернышевск-Крм.pdf", "нечётное", "Карымская — Чернышевск-Забайкальский"),
]


def build_picket_map(words):
    """Строит (км-плашки, y их строки, отсортированный [(x_центр, км, пк)]) — общая
    геометрия и для сигналов, и для названий станций (см. extract_stations_from_page)."""
    km_words = []
    for w in words:
        x0, y0, x1, y1, text = w[:5]
        if re.fullmatch(r'\d{4}', text) and (y1 - y0) > 18:
            km_words.append((x0, y0, x1, y1, int(text)))
    km_words.sort(key=lambda w: w[0])
    if not km_words:
        return [], None, []
    km_row_y0 = km_words[0][1]

    picket_words = []
    for w in words:
        x0, y0, x1, y1, text = w[:5]
        t = text.strip()
        if re.fullmatch(r'\d{1,2}', t) and (y1 - y0) < 12:
            val = int(t)
            if 1 <= val <= 10 and abs(y0 - km_row_y0) < 20:
                picket_words.append(((x0 + x1) / 2, val))

    def find_km_for_x(x):
        for i in range(len(km_words)):
            left = km_words[i][0]
            right = km_words[i + 1][0] if i + 1 < len(km_words) else float('inf')
            if left - 3 <= x < right - 3:
                return km_words[i][4]
        return None

    picket_map = []
    for xc, pk in picket_words:
        km = find_km_for_x(xc)
        if km is not None:
            picket_map.append((xc, km, pk))
    picket_map.sort()

    return km_words, km_row_y0, picket_map


def nearest_picket(picket_map, xc):
    xs = [p[0] for p in picket_map]
    idx = bisect.bisect_left(xs, xc)
    candidates = []
    if idx > 0:
        candidates.append(picket_map[idx - 1])
    if idx < len(picket_map):
        candidates.append(picket_map[idx])
    best = min(candidates, key=lambda p: abs(p[0] - xc))
    return best, round(abs(best[0] - xc), 1)


# Название станции — тем же жирным шрифтом и той же высоты (~21.6pt), что и км-плашки,
# но буквами, не цифрами. Отдельно от сигналов: сигналы фильтруются высотой < 20pt именно
# чтобы НЕ подхватывать названия станций — так что здесь диапазон высот другой (18-25pt),
# а не "то, что осталось". Диапазон верхней границы (25pt) отсекает более крупные надписи
# вроде "Остановка"/"Опробование" (тормозов) — те высотой 35-46pt, это другая подпись, не
# название станции.
STATION_PATTERN = re.compile(r'^[А-ЯЁ][а-яёА-ЯЁ-]{2,}$')


def extract_stations_from_page(page):
    words = page.get_text('words')
    km_words, km_row_y0, picket_map = build_picket_map(words)
    if km_row_y0 is None or len(picket_map) < 5:
        return []

    stations = []
    for w in words:
        x0, y0, x1, y1, text = w[:5]
        t = text.strip()
        h = y1 - y0
        if not (18 <= h <= 25):
            continue
        if not STATION_PATTERN.fullmatch(t):
            continue
        xc = (x0 + x1) / 2
        best, off = nearest_picket(picket_map, xc)
        stations.append({'name': t, 'km': best[1], 'pk': best[2], 'off': off})

    dedup = {}
    for r in stations:
        key = (r['name'], r['km'], r['pk'])
        if key not in dedup or r['off'] < dedup[key]['off']:
            dedup[key] = r
    return sorted(dedup.values(), key=lambda r: (r['km'], r['pk']))


def extract_stations_from_pdf(path):
    doc = fitz.open(path)
    all_results = []
    for page in doc:
        all_results.extend(extract_stations_from_page(page))
    dedup = {}
    for r in all_results:
        key = (r['name'], r['km'], r['pk'])
        if key not in dedup or r['off'] < dedup[key]['off']:
            dedup[key] = r
    return sorted(dedup.values(), key=lambda r: (r['km'], r['pk']))


def extract_signals_from_page(page):
    words = page.get_text('words')
    km_words, km_row_y0, picket_map = build_picket_map(words)
    if km_row_y0 is None:
        return []

    if len(picket_map) < 5:
        return []

    average_labels = []
    for w in words:
        x0, y0, x1, y1, text = w[:5]
        if text.strip() == 'средний':
            average_labels.append(((x0 + x1) / 2, y0))

    def is_average_track(xc, y1):
        for lx, ly0 in average_labels:
            if abs(lx - xc) <= 12 and 0 <= (ly0 - y1) <= 5:
                return True
        return False

    signals = []
    for w in words:
        x0, y0, x1, y1, text = w[:5]
        t = text.strip()
        if (y1 - y0) >= 20:
            continue
        if y0 >= km_row_y0 - 20:
            continue
        if t in NON_SIGNAL_EXACT:
            continue
        if not SIG_PATTERN.fullmatch(t):
            continue
        if re.fullmatch(r'\d{1,2}', t) and int(t) > 21:
            continue
        xc = (x0 + x1) / 2
        track = 'средний' if is_average_track(xc, y1) else None
        signals.append((xc, y0, t, track))

    results = []
    for xc, y0, name, track in signals:
        best, off = nearest_picket(picket_map, xc)
        results.append({'name': name, 'km': best[1], 'pk': best[2], 'track': track, 'off': off})

    # Схлопнуть дубли той же литеры на том же пикете и пути (см. докстринг
    # модуля — но НЕ схлопывать разные пути между собой, см. "средний").
    dedup = {}
    for r in results:
        key = (r['name'], r['km'], r['pk'], r['track'])
        if key not in dedup or r['off'] < dedup[key]['off']:
            dedup[key] = r
    return sorted(dedup.values(), key=lambda r: (r['km'], r['pk']))


def extract_signals_from_pdf(path):
    doc = fitz.open(path)
    all_results = []
    for page in doc:
        all_results.extend(extract_signals_from_page(page))
    # Финальный дедуп по всему документу (на случай дубля на стыке страниц).
    dedup = {}
    for r in all_results:
        key = (r['name'], r['km'], r['pk'], r['track'])
        if key not in dedup or r['off'] < dedup[key]['off']:
            dedup[key] = r
    return sorted(dedup.values(), key=lambda r: (r['km'], r['pk']))


def main():
    src_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    signals_out = sys.argv[2] if len(sys.argv) > 2 else "track_signals.json"
    stations_out = sys.argv[3] if len(sys.argv) > 3 else "track_stations.json"

    all_signals = []
    all_stations = []
    for fname, direction, segment in SOURCE_FILES:
        results = extract_signals_from_pdf(f"{src_dir}/{fname}")
        stations = extract_stations_from_pdf(f"{src_dir}/{fname}")
        print(f"{fname}: {len(results)} signals, {len(stations)} stations")
        for r in results:
            all_signals.append({
                "segment": segment,
                "direction": direction,
                "source": fname,
                "name": r["name"],
                "km": r["km"],
                "pk": r["pk"],
                "track": r["track"],
            })
        for s in stations:
            all_stations.append({
                "segment": segment,
                "direction": direction,
                "source": fname,
                "name": s["name"],
                "km": s["km"],
                "pk": s["pk"],
            })

    with open(signals_out, "w", encoding="utf-8") as f:
        json.dump(all_signals, f, ensure_ascii=False, separators=(",", ":"))
    with open(stations_out, "w", encoding="utf-8") as f:
        json.dump(all_stations, f, ensure_ascii=False, separators=(",", ":"))

    print(f"\nTotal: {len(all_signals)} signals, {len(all_stations)} stations")
    print(f"Wrote {signals_out}, {stations_out}")


if __name__ == "__main__":
    main()
