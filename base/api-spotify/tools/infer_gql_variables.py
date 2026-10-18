"""Guess the variable names of GraphQL operations from the Web Player bundles.

    python infer_gql_variables.py > docs/api/gql-variables-inferred.json

Each operation is defined as `X=new Q("name","query","hash",...)` and used as
`loader(X,{key:value,...})`. This finds the identifier bound to each definition and
collects the keys of the object literals passed right after it. Heuristic: check against a
real call before relying on a guess.
"""
import json, re, sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from refresh_gql_ops import get, script_json  # noqa: E402

DEF = re.compile(r'([\w$]+)\s*=\s*new [\w$]+(?:\.[\w$]+)?\("(\w+)","(query|mutation|subscription)","[0-9a-f]{64}"')
KEY = re.compile(r'(?:^|[,{])\s*([A-Za-z_$][\w$]*)\s*:')


def object_keys(text, start):
    """Top-level keys of the object literal starting at text[start] == '{'."""
    depth, i, keys, buf = 0, start, [], []
    while i < len(text) and i < start + 4000:
        c = text[i]
        if c in "{[(":
            depth += 1
        elif c in "}])":
            depth -= 1
            if depth == 0:
                break
        if depth == 1:
            buf.append(c)
        elif depth > 1 and c in "{[(":
            buf.append("…")
        i += 1
    flat = "".join(buf)
    keys = [k for k in KEY.findall("{" + flat[1:]) if k]
    shorthand = re.findall(r'(?:^|,)\s*([A-Za-z_$][\w$]*)\s*(?=,|$)', flat[1:])
    return sorted(set(keys) | {s for s in shorthand if not s.startswith("…")})


def main():
    html = get("https://open.spotify.com/")
    cdn = script_json(html, "__CDN_FILE_URLS__") or {}
    urls = set(re.findall(r'https://open\.spotifycdn\.com/cdn/build/web-player/[^"\']+?\.js', html))
    urls |= {u for u in (cdn.values() if isinstance(cdn, dict) else cdn) if isinstance(u, str) and u.endswith(".js")}
    with ThreadPoolExecutor(8) as pool:
        bodies = list(pool.map(get, sorted(urls)))
    result = {}
    for body in bodies:
        for m in DEF.finditer(body):
            ident, name = m.group(1), m.group(2)
            found = result.setdefault(name, set())
            # Usages in the same bundle: `(ident,{` or `, ident,{`.
            for u in re.finditer(r'[(,]\s*' + re.escape(ident) + r'\s*,\s*\{', body):
                found |= set(object_keys(body, u.end() - 1))
    out = {k: sorted(v) for k, v in sorted(result.items())}
    json.dump(out, sys.stdout, indent=1)
    print(f"{sum(1 for v in out.values() if v)} / {len(out)} operations with inferred variables", file=sys.stderr)


if __name__ == "__main__":
    main()
