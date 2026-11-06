#!/usr/bin/env python3
"""
gorodwatch — выгрузка и мониторинг объявлений «Брошенное транспортное средство»
с gorod.mos.ru (публичные данные карты обращений). Только стандартная библиотека.

  python3 gorodwatch.py sync --since 2025-01-01      # выкачать историю в gorod.db
  python3 gorodwatch.py watch --interval 600         # мониторинг новых + уведомления
  python3 gorodwatch.py export out.csv               # выгрузка в CSV
  python3 gorodwatch.py stats                        # сколько записей

Уведомления: всегда в консоль; Telegram — если заданы TG_TOKEN и TG_CHAT;
Termux — флаг --termux (нужен пакет termux-api).
"""
import argparse, csv, datetime as dt, http.cookiejar, json, os, shutil, sqlite3
import subprocess, sys, time, urllib.error, urllib.parse, urllib.request

BASE = os.environ.get("GOROD_BASE", "https://gorod.mos.ru")
DB_PATH = os.environ.get("GOROD_DB", "gorod.db")
UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0 Mobile Safari/537.36")
STATUSES = [
    {"value": "affirmed", "label": "Проблема в работе"},
    {"value": "resolved", "label": "Проблема решена"},
    {"value": "actions_taken", "label": "Приняты меры по решению проблемы"},
    {"value": "not_found", "label": "Проблема не обнаружена"},
]
KEYWORDS = ("брошен", "разукомплект")
FALLBACK = [(11122, 986, "Брошенное транспортное средство во дворе")]
MSK = dt.timezone(dt.timedelta(hours=3))


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---------------------------------------------------------------- HTTP
class Api:
    def __init__(self, delay=1.0):
        self.delay, self._last = delay, 0.0
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        try:  # «прогрев»: получить cookies, как делает браузер
            self.opener.open(urllib.request.Request(
                BASE + "/map-messages", headers={"User-Agent": UA}), timeout=30).read(1)
        except Exception as e:
            log("прогрев не удался (не критично):", e)

    def post(self, path, body, retries=5):
        for attempt in range(retries):
            wait = self.delay - (time.time() - self._last)
            if wait > 0:
                time.sleep(wait)
            self._last = time.time()
            req = urllib.request.Request(
                BASE + path,
                data=json.dumps(body, ensure_ascii=False).encode(),
                headers={"Content-Type": "application/json",
                         "Accept": "application/json, text/plain, */*",
                         "Origin": BASE, "Referer": BASE + "/map-messages",
                         "User-Agent": UA})
            try:
                with self.opener.open(req, timeout=60) as r:
                    data = json.loads(r.read().decode("utf-8"))
                if data.get("status") != "success":
                    raise RuntimeError(f"{path}: {str(data)[:300]}")
                return data["data"]
            except urllib.error.HTTPError as e:
                snippet = e.read()[:300].decode("utf-8", "replace")
                if e.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                    pause = 5 * 2 ** attempt
                    log(f"HTTP {e.code} на {path}, пауза {pause}с")
                    time.sleep(pause)
                    continue
                raise RuntimeError(f"HTTP {e.code} {path}: {snippet}")
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt < retries - 1:
                    time.sleep(5 * 2 ** attempt)
                    continue
                raise RuntimeError(f"сеть {path}: {e}")


# ---------------------------------------------------------------- темы
def discover_nodes(api):
    """Ищем в дереве тем узлы с «брошен…»; возвращаем [(tree_item_id, theme_id, title)]."""
    try:
        tree = api.post("/data/theme/tree", {"map": True})
    except Exception as e:
        log("дерево тем недоступно, беру известную тему 986:", e)
        return FALLBACK
    found, seen = [], set()

    def walk(nodes):
        for n in nodes or []:
            title = (n.get("title") or "")
            if n.get("theme_id") and any(k in title.lower() for k in KEYWORDS) \
                    and n["theme_id"] not in seen:
                seen.add(n["theme_id"])
                found.append((n["id"], n["theme_id"], title))
            walk(n.get("children"))
    walk(tree)
    return found or FALLBACK


# ---------------------------------------------------------------- выборка
def fetch(api, node, theme, t_from, t_to, known=None, stop_on_known=False, max_pages=2000):
    seen_here, total, page = set(), 0, 1
    while page <= max_pages:
        d = api.post("/data/messages/map", {
            "page": page, "status": [s["value"] for s in STATUSES],
            "initialStatus": STATUSES, "theme": [theme],
            "date": {"from": t_from, "to": t_to}, "region": 0, "district": 0,
            "only_my": False, "ids": [], "tree_item_id": node})
        items = d.get("items") or []
        if not items:
            return
        fresh = [i for i in items if i["id"] not in seen_here]
        if not fresh:          # сервер вернул ту же страницу — защита от цикла
            return
        for i in fresh:
            seen_here.add(i["id"])
            yield i
        total += len(items)
        count = d.get("count")
        if count is not None and total >= count:
            return
        if stop_on_known and known is not None and all(i["id"] in known for i in items):
            return
        page += 1


# ---------------------------------------------------------------- БД
SCHEMA = """
CREATE TABLE IF NOT EXISTS messages(
  id INTEGER PRIMARY KEY, theme_id INT, theme_name TEXT,
  created INT, published INT, status_code TEXT, status_name TEXT,
  text TEXT, place TEXT, address TEXT, lat REAL, lon REAL,
  user_name TEXT, photos TEXT, first_seen INT, raw TEXT);
CREATE INDEX IF NOT EXISTS ix_pub ON messages(published);
"""
DROP_KEYS = {"attrsSource", "timeline", "noticeForLK", "attachments"}


def db_open():
    con = sqlite3.connect(DB_PATH)
    con.executescript(SCHEMA)
    return con


def row_of(it):
    co = it.get("coordinates") or [None, None]
    photos = [a.get("thumb_lg_w") or a.get("path_jpg_w") for a in it.get("attachments") or []]
    raw = {k: v for k, v in it.items() if k not in DROP_KEYS}
    return (it["id"], it.get("themeId"), it.get("themeName"), it.get("createdDate"),
            it.get("publishDate"), it.get("statusCode"), it.get("statusName"),
            it.get("text") or "", it.get("name"), it.get("address"),
            co[1], co[0], it.get("userName"),
            json.dumps([p for p in photos if p], ensure_ascii=False),
            int(time.time()), json.dumps(raw, ensure_ascii=False))


def save(con, items):
    """Возвращает список НОВЫХ записей (dict-ы строк)."""
    new = []
    for it in items:
        r = row_of(it)
        cur = con.execute("SELECT status_code FROM messages WHERE id=?", (r[0],)).fetchone()
        if cur is None:
            con.execute("INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", r)
            new.append(r)
        elif cur[0] != r[5]:
            con.execute("UPDATE messages SET status_code=?,status_name=?,raw=? WHERE id=?",
                        (r[5], r[6], r[15], r[0]))
    con.commit()
    return new


# ---------------------------------------------------------------- уведомления
def fmt_ts(ts):
    return dt.datetime.fromtimestamp(ts, MSK).strftime("%d.%m.%Y %H:%M") if ts else "?"


def message_text(r):
    photos = json.loads(r[13])
    lines = [f"🚗 {r[2]}", f"📍 {r[8]}", r[9] or "", f"🕒 {fmt_ts(r[4] or r[3])}  ({r[6]})"]
    if r[7]:
        lines.append(r[7][:400])
    if r[10] is not None:
        lines.append(f"https://yandex.ru/maps/?pt={r[11]},{r[10]}&z=18&l=map")
    if photos:
        lines.append(f"Фото: {photos[0]}")
    lines.append(f"№ {r[0]}")
    return "\n".join(l for l in lines if l)


def notify(text, termux=False):
    print("-" * 40 + "\n" + text, flush=True)
    tok, chat = os.environ.get("TG_TOKEN"), os.environ.get("TG_CHAT")
    if tok and chat:
        try:
            data = urllib.parse.urlencode({"chat_id": chat, "text": text,
                                           "disable_web_page_preview": "false"}).encode()
            urllib.request.urlopen(
                f"https://api.telegram.org/bot{tok}/sendMessage", data, timeout=30).read()
        except Exception as e:
            log("Telegram:", e)
    if termux and shutil.which("termux-notification"):
        subprocess.run(["termux-notification", "--title", "Брошенное авто",
                        "--content", text.split("\n")[1][:200]], check=False)


# ---------------------------------------------------------------- команды
def day_chunks(start, end, step):
    cur = start
    while cur < end:
        nxt = min(cur + dt.timedelta(days=step), end)
        yield int(cur.timestamp()), int(nxt.timestamp()) - 1
        cur = nxt


def cmd_sync(a):
    api, con = Api(a.delay), db_open()
    start = dt.datetime.strptime(a.since, "%Y-%m-%d").replace(tzinfo=MSK)
    end = (dt.datetime.strptime(a.until, "%Y-%m-%d").replace(tzinfo=MSK)
           if a.until else dt.datetime.now(MSK) + dt.timedelta(days=1))
    nodes = discover_nodes(api)
    log("темы:", [(n[1], n[2]) for n in nodes])
    total_new = 0
    for node, theme, title in nodes:
        for f, t in day_chunks(start, end, a.chunk_days):
            got = list(fetch(api, node, theme, f, t))
            new = save(con, got)
            total_new += len(new)
            log(f"{title[:40]} | {dt.datetime.fromtimestamp(f, MSK):%Y-%m-%d}: "
                f"получено {len(got)}, новых {len(new)}")
    log(f"готово, новых записей: {total_new}, всего в БД: "
        f"{con.execute('SELECT COUNT(*) FROM messages').fetchone()[0]}")


def watch_cycle(api, con, nodes, days, termux, baseline_quiet):
    now = dt.datetime.now(MSK)
    f, t = int((now - dt.timedelta(days=days)).timestamp()), int((now + dt.timedelta(days=1)).timestamp())
    known = {r[0] for r in con.execute("SELECT id FROM messages")}
    empty = not known
    all_new = []
    for node, theme, _ in nodes:
        all_new += save(con, fetch(api, node, theme, f, t, known, stop_on_known=True))
    if empty and baseline_quiet:
        log(f"первичная загрузка: {len(all_new)} записей сохранено без уведомлений")
        return
    for r in sorted(all_new, key=lambda r: r[4] or r[3] or 0):
        notify(message_text(r), termux)
    log(f"цикл: новых {len(all_new)}")


def cmd_watch(a):
    api, con = Api(a.delay), db_open()
    nodes = discover_nodes(api)
    log("мониторинг тем:", [(n[1], n[2]) for n in nodes])
    fails = 0
    while True:
        try:
            watch_cycle(api, con, nodes, a.days, a.termux, not a.notify_first)
            fails = 0
        except Exception as e:
            fails += 1
            log(f"ошибка цикла ({fails}):", e)
            if fails == 5:
                notify(f"⚠️ gorodwatch: 5 ошибок подряд. Последняя: {e}", a.termux)
        if a.once:
            return
        time.sleep(a.interval)


def cmd_export(a):
    con = db_open()
    cols = ["id", "theme_name", "created", "published", "status_name", "text",
            "place", "address", "lat", "lon", "user_name", "photos"]
    with open(a.out, "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh)
        w.writerow(cols[:2] + ["created_msk", "published_msk"] + cols[4:])
        for r in con.execute(f"SELECT {','.join(cols)} FROM messages ORDER BY published DESC"):
            r = list(r)
            r[2], r[3] = fmt_ts(r[2]), fmt_ts(r[3])
            w.writerow(r)
    log("записано:", a.out)


def cmd_stats(a):
    con = db_open()
    n, lo, hi = con.execute("SELECT COUNT(*),MIN(published),MAX(published) FROM messages").fetchone()
    log(f"записей: {n}; с {fmt_ts(lo)} по {fmt_ts(hi)}")
    for s, c in con.execute("SELECT status_name,COUNT(*) FROM messages GROUP BY 1 ORDER BY 2 DESC"):
        print(f"  {s}: {c}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--delay", type=float, default=1.0, help="пауза между запросами, сек (не ставьте меньше 0.5)")
    sp = p.add_subparsers(dest="cmd", required=True)
    s = sp.add_parser("sync"); s.set_defaults(fn=cmd_sync)
    s.add_argument("--since", default="2025-01-01"); s.add_argument("--until")
    s.add_argument("--chunk-days", type=int, default=7)
    w = sp.add_parser("watch"); w.set_defaults(fn=cmd_watch)
    w.add_argument("--interval", type=int, default=600); w.add_argument("--days", type=int, default=3)
    w.add_argument("--termux", action="store_true"); w.add_argument("--once", action="store_true")
    w.add_argument("--notify-first", action="store_true", help="уведомлять и при первом запуске")
    e = sp.add_parser("export"); e.set_defaults(fn=cmd_export); e.add_argument("out")
    st = sp.add_parser("stats"); st.set_defaults(fn=cmd_stats)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
