"""Merge everything known about each GraphQL operation into one schema file.

    python build_gql_schema.py <probe.json> > base/api-spotify/src/main/resources/spotify/gql-schema.json

Sources:
- probe.json: answers to each operation sent with empty variables. The server rejects the
  call before running it and names every required variable with its GraphQL type.
  Only probe queries: a mutation with no required variable would really run.
- docs/api/gql-variables.json: variables the Web Player really sent (captures), with one example.
- docs/api/gql-variables-inferred.json: names guessed from the bundles (kept only when short).

Output: {op: {"kind", "required": {name: type}, "optional": [names], "example": {...}}}
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OPS = Path(__file__).resolve().parents[1] / "src/main/resources/spotify/gql-ops.json"
MISSING = re.compile(r"missing variable `\$(\w+)`: for required GraphQL type `([^`]+)`")


def main(probe_path):
    ops = json.loads(OPS.read_text())["operations"]
    probe = json.load(open(probe_path))
    real = json.loads((ROOT / "docs/api/gql-variables.json").read_text())
    inferred_path = ROOT / "docs/api/gql-variables-inferred.json"
    inferred = json.loads(inferred_path.read_text()) if inferred_path.exists() else {}
    out = {}
    for name, op in ops.items():
        p = probe.get(name, {})
        required = dict(MISSING.findall(p.get("body", "")))
        known = set(real.get(name, {}).get("variables", []))
        guess = inferred.get(name) or []
        if len(guess) > 8 or any(len(g) > 30 or g[:1].isupper() for g in guess):
            guess = []  # noisy match on minified code
        optional = sorted((known | set(guess)) - set(required))
        out[name] = {
            "kind": op["kind"],
            "required": required,
            "optional": optional,
            "example": real.get(name, {}).get("example", {}),
            "probed": p.get("status") is not None,
        }
    json.dump(out, sys.stdout, indent=1, ensure_ascii=False)
    print(f"{len(out)} ops, {sum(1 for v in out.values() if v['probed'])} probed, "
          f"{sum(1 for v in out.values() if v['example'])} with a real example", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1])
