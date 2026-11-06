#!/usr/bin/env python3
"""Разбор дампа из GorodWatcher: показывает, какие запросы возвращают списки объявлений.
Запуск: python3 tools/analyze_dump.py ~/storage/downloads/gorod_dump_*.json
"""
import json, sys, glob

paths = []
for a in sys.argv[1:]:
    paths += glob.glob(a)
if not paths:
    sys.exit("укажите файл дампа")

for p in sorted(paths):
    d = json.load(open(p, encoding="utf-8"))
    print(f"== {p}  страница: {d.get('page')}  записей: {len(d['entries'])}")
    seen = set()
    for e in d["entries"]:
        key = (e["method"], e["url"])
        if key in seen:
            continue
        seen.add(key)
        arr = e.get("arr")
        mark = "★" if arr and arr["len"] > 1 else " "
        print(f"{mark} {e['method']:5} {e['status']} {e['len']:>7}B {e['url'][:110]}")
        if arr:
            try:
                keys = list(json.loads(arr["first"]).keys())
            except Exception:
                keys = "?"
            print(f"      список в {arr['path']}, элементов: {arr['len']}, поля: {keys}")
        if e.get("meta"):
            print(f"      meta: {json.dumps(e['meta'], ensure_ascii=False)[:200]}")
