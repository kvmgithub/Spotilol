"""Collect the variables the Web Player really sends for each GraphQL operation.

    python extract_gql_variables.py <capture.jsonl>... > docs/api/gql-variables.json

Captures are the JSONL files of the capture recorder. For each operationName, keeps one
example of variables (the richest seen) and the union of variable names. Values that look
like the account's own ids are replaced with placeholders, so the output can live in the repo.
"""
import base64, json, re, sys

def scrub(v, user_ids):
    if isinstance(v, dict):
        return {k: scrub(x, user_ids) for k, x in v.items()}
    if isinstance(v, list):
        return [scrub(x, user_ids) for x in v]
    if isinstance(v, str):
        if re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", v):
            return "{uuid}"  # sp_t cookie, interaction ids
        for uid in user_ids:
            v = v.replace(uid, "{user}")
        if v.startswith("spotify:user:"):
            return "spotify:user:{user}"
    return v

def main(paths):
    ops, user_ids = {}, set()
    for path in paths:
        for line in open(path):
            e = json.loads(line)
            if e.get("kind") != "http":
                continue
            m = re.search(r"/user/([a-z0-9]{20,})/", e["url"])
            if m:
                user_ids.add(m.group(1))
            if "pathfinder" not in e["url"] or not e.get("post"):
                continue
            try:
                body = json.loads(base64.b64decode(e["post"]))
            except Exception:
                continue
            name = body.get("operationName")
            if not name:
                continue
            variables = body.get("variables") or {}
            slot = ops.setdefault(name, {"names": set(), "example": {}})
            slot["names"] |= set(variables)
            if len(json.dumps(variables)) > len(json.dumps(slot["example"])):
                slot["example"] = variables
    out = {name: {"variables": sorted(s["names"]), "example": scrub(s["example"], user_ids)}
           for name, s in sorted(ops.items())}
    json.dump(out, sys.stdout, indent=1, ensure_ascii=False)
    print(f"{len(out)} operations", file=sys.stderr)

if __name__ == "__main__":
    main(sys.argv[1:])
