#!/usr/bin/env python3
"""
Извлекает форму рельефа (профиль высот) из тех же 4 PDF-профилей пути, что и
`parse_track_profile.py` — но другим способом: рельеф на этих страницах не текст, а
чёрная волнистая ВЕКТОРНАЯ кривая (see PyMuPDF `page.get_drawings()`), поэтому здесь не
подходит текстовый разбор `page.get_text('words')`.

Как искать саму кривую: на каждой странице она — самый широкий (>85% ширины страницы)
почти-чёрный (RGB ≈ 0.137,0.122,0.125 — то же перо, что рисует иконки светофоров и
рамки) векторный путь с заметной высотой (>5pt, чтобы не спутать с горизонтальными
линиями сетки/рамок нулевой высоты). Состоит из чередующихся отрезков ('l') и кривых
Безье 3-го порядка ('c') — обе формы точек сэмплируются в один список (x, y),
сортируются по x, и через ту же сетку "км/пикет" (`build_picket_map` из
`parse_track_profile.py`), что и у сигналов, каждому пикету присваивается высота кривой
в этой точке (линейная интерполяция между двумя ближайшими сэмплами).

Проверено визуально: наложил извлечённую кривую (магента) поверх рендера первой
страницы "Хилок-Крм.pdf" — совпадает с чёрной линией рельефа пиксель в пиксель по всей
странице.

ВАЖНО — САМОЕ ГЛАВНОЕ ОГРАНИЧЕНИЕ ЭТОГО ШАГА (не решено, и вряд ли решаемо без новых
исходных данных):

1. На этих страницах НЕТ шкалы высот вообще — ни оси с числами (метры/футы), ни
   легенды с масштабом ("1мм = Xм"), ни единой подписанной точки высоты. Проверено:
   весь текст страницы просмотрен, никаких числовых меток рядом с кривой рельефа нет
   (кроме сигналов/пикетов/уклона, которые не про высоту). Поэтому высота здесь — это
   координата в pt самого PDF, НЕ метры и вообще не физическая единица.

2. Что хуже: эта величина в pt даже НЕ СРАВНИМА между разными страницами одного
   документа. Проверено эмпирически: взял высоту кривой (относительно строки км-плашек)
   на границе каждых двух соседних страниц (последняя точка одной = первая точка
   следующей, физически одна и та же точка пути) — разрыв достигает 60+ pt, при том что
   реальный перепад высоты пути на 100-200 м расстояния между соседними пикетами
   заведомо не может быть таким большим. Единственное объяснение — каждая страница
   рисуется с собственным, независимо подобранным вертикальным масштабом/сдвигом (чтобы
   волна рельефа each page красиво заполняла отведённое место), а не единым сквозным
   масштабом на весь маршрут. Поэтому высоты этого скрипта СРАВНИМЫ ТОЛЬКО В ПРЕДЕЛАХ
   ОДНОЙ СТРАНИЦЫ (одного `source`+`page`) — сшивать их в один сквозной график поправкой
   на постоянное смещение НЕЛЬЗЯ (раз масштаб тоже плавает, а не только сдвиг, простая
   поправка сместила бы форму, а не только сдвинула её).
   Практически это не страшно: видимый на графике участок пути в приложении — это всего
   ~4.6 км (BEHIND_M+AHEAD_M в мокапе), а не 14-15 км одной страницы, так что почти
   всегда весь видимый рельеф целиком лежит на одной странице; сшивка между страницами
   нужна только у самой границы, и это отдельная, не решённая здесь задача.

3. Страница 12 "Хилок-Крм.pdf" (сложная станция Тургутуй — Яблоновая, см. README шаг 13)
   не даёт кривой рельефа вообще — либо её там нет, либо она разбита посторонней
   графикой станции на кусочки короче порога 85% ширины страницы. Для этой страницы
   результат пустой (15 км без рельефа).

Отдельно от этого шага: красная зубчатая строка (значения уклона, ‰) под км-рядом —
это ДРУГИЙ, более полезный для реальной физики источник высоты (уклон — это и есть
производная высоты по пути, причём с числом, а не только формой), но там своя
нерешённая проблема — понять знак (подъём/спуск) каждого числа по рисунку зубца
надёжно не получилось без ошибочных допущений, поэтому эта отдельная задача
сознательно не начата в этом шаге.
"""
import re
import sys
import json
import bisect

import fitz

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from parse_track_profile import build_picket_map, SOURCE_FILES  # noqa: E402

TERRAIN_COLOR = (0.137, 0.122, 0.125)
TERRAIN_COLOR_TOL = 0.01


def get_terrain_drawing(page):
    pw = page.rect.width
    drawings = page.get_drawings()
    black = [
        d for d in drawings
        if d.get('color') and abs(d['color'][0] - TERRAIN_COLOR[0]) < TERRAIN_COLOR_TOL
    ]
    candidates = [d for d in black if d['rect'].width > pw * 0.85 and d['rect'].height > 5]
    if not candidates:
        return None
    return max(candidates, key=lambda d: len(d['items']))


def sample_curve(items):
    """Разворачивает отрезки/кривые Безье в список (x, y), отсортированный по x."""
    pts = []
    for item in items:
        kind = item[0]
        if kind == 'l':
            p0, p1 = item[1], item[2]
            pts.append((p0.x, p0.y))
            pts.append((p1.x, p1.y))
        elif kind == 'c':
            p0, p1, p2, p3 = item[1], item[2], item[3], item[4]
            for t in (0.0, 0.2, 0.4, 0.6, 0.8, 1.0):
                mt = 1 - t
                x = mt**3 * p0.x + 3 * mt**2 * t * p1.x + 3 * mt * t**2 * p2.x + t**3 * p3.x
                y = mt**3 * p0.y + 3 * mt**2 * t * p1.y + 3 * mt * t**2 * p2.y + t**3 * p3.y
                pts.append((x, y))
    pts.sort(key=lambda p: p[0])
    return pts


def extract_relief_from_page(page):
    words = page.get_text('words')
    km_words, km_row_y0, picket_map = build_picket_map(words)
    if km_row_y0 is None or len(picket_map) < 5:
        return []

    terrain = get_terrain_drawing(page)
    if terrain is None:
        return []

    pts = sample_curve(terrain['items'])
    xs = [p[0] for p in pts]

    results = []
    for xc, km, pk in picket_map:
        idx = bisect.bisect_left(xs, xc)
        if idx == 0 or idx >= len(pts):
            continue
        x0, y0 = pts[idx - 1]
        x1, y1 = pts[idx]
        y = y0 if x1 == x0 else y0 + (y1 - y0) * (xc - x0) / (x1 - x0)
        results.append({'km': km, 'pk': pk, 'height_pt': round(km_row_y0 - y, 1)})
    return results


def main():
    src_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
    out_path = sys.argv[2] if len(sys.argv) > 2 else 'track_relief.json'

    all_entries = []
    for fname, direction, segment in SOURCE_FILES:
        doc = fitz.open(f'{src_dir}/{fname}')
        n_pages_ok = 0
        for page_num, page in enumerate(doc):
            results = extract_relief_from_page(page)
            if results:
                n_pages_ok += 1
            for r in results:
                all_entries.append({
                    'segment': segment,
                    'direction': direction,
                    'source': fname,
                    'page': page_num,
                    'km': r['km'],
                    'pk': r['pk'],
                    'height_pt': r['height_pt'],
                })
        print(f'{fname}: {n_pages_ok}/{len(doc)} pages with a relief curve')

    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(all_entries, f, ensure_ascii=False, separators=(',', ':'))

    print(f'\nTotal: {len(all_entries)} points')
    print(f'Wrote {out_path}')


if __name__ == '__main__':
    main()
