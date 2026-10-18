"""Rebuild the table of every Web Player GraphQL operation (name, kind, sha256 hash).

Reads only public files: the open.spotify.com page and its JS bundles on the CDN.
No account or token is needed.

    python refresh_gql_ops.py [out.json]

Output: {"buildVersion", "clientVersion", "operations": {name: {"kind", "hash"}}}.
The app can run the same steps at startup and refresh its table when buildVersion changes.
"""
import base64, json, re, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
OP_DEF = re.compile(r'new [\w$]+(?:\.[\w$]+)?\("(\w+)","(query|mutation|subscription)","([0-9a-f]{64})"')


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def script_json(html, script_id):
    m = re.search(rf'<script id="{script_id}" type="text/plain">([^<]+)</script>', html)
    if not m:
        m = re.search(rf'<script id="{script_id}"[^>]*>([^<]+)</script>', html)
    if not m:
        return None
    raw = m.group(1).strip()
    try:
        return json.loads(base64.b64decode(raw))
    except Exception:
        return json.loads(raw)


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else None
    html = get("https://open.spotify.com/")
    server = script_json(html, "appServerConfig") or {}
    cdn = script_json(html, "__CDN_FILE_URLS__") or {}
    urls = set(re.findall(r'https://open\.spotifycdn\.com/cdn/build/web-player/[^"\']+?\.js', html))
    urls |= {u for u in (cdn.values() if isinstance(cdn, dict) else cdn) if isinstance(u, str) and u.endswith(".js")}
    if not urls:
        raise SystemExit("no bundle URL found in the page")
    ops = {}
    with ThreadPoolExecutor(8) as pool:
        for body in pool.map(lambda u: get(u), sorted(urls)):
            for name, kind, h in OP_DEF.findall(body):
                ops[name] = {"kind": kind, "hash": h}
    table = {
        "buildVersion": server.get("buildVersion"),
        "clientVersion": server.get("clientVersion"),
        "bundles": len(urls),
        "operations": dict(sorted(ops.items())),
    }
    text = json.dumps(table, indent=1)
    if out_path:
        open(out_path, "w").write(text + "\n")
    print(f"{len(ops)} operations from {len(urls)} bundles, build {table['buildVersion']}", file=sys.stderr)
    if not out_path:
        print(text)


if __name__ == "__main__":
    main()
