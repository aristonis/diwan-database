#!/bin/sh
# Copies build/api into <docs-repo>/docs/api/<target>/ and refreshes versions.json and alias pages.
# Usage: scripts/publish-api.sh <docs-repo-path> <dev|x.y.z>
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <docs-repo-path> <dev|x.y.z>" >&2
  exit 2
fi
docs_repo=$1
target=$2
here=$(cd "$(dirname "$0")/.." && pwd)
build="$here/build/api"

if [ "$target" != dev ] && ! printf '%s' "$target" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "target must be dev or x.y.z, got: $target" >&2
  exit 2
fi
[ -f "$build/index.html" ] || { echo "no build at $build; run ./gradlew apiDocs first" >&2; exit 1; }
[ -d "$docs_repo/docs" ] || { echo "not a docs repo (no docs/ folder): $docs_repo" >&2; exit 1; }

api="$docs_repo/docs/api"
mkdir -p "$api"
if [ "$target" = dev ]; then
  rm -rf "${api:?}/dev"
elif [ -e "$api/$target" ]; then
  echo "refusing to overwrite released build $api/$target" >&2
  exit 1
fi
cp -R "$build" "$api/$target"

python3 -I - "$api" "$target" <<'PY'
import json, os, sys

api, target = sys.argv[1], sys.argv[2]
path = os.path.join(api, "versions.json")
data = {"dev": False, "releases": []}
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
if target == "dev":
    data["dev"] = True
elif target not in data["releases"]:
    data["releases"].append(target)


def key(v):
    return tuple(int(p) for p in v.split("."))


releases = sorted(data["releases"], key=key, reverse=True)
with open(path, "w", encoding="utf-8") as f:
    json.dump({"dev": bool(data.get("dev")), "releases": releases}, f, indent=2)
    f.write("\n")


def alias(folder, dest, label):
    os.makedirs(folder, exist_ok=True)
    url = os.path.relpath(os.path.join(api, dest), folder).replace(os.sep, "/") + "/index.html"
    html = ('<!DOCTYPE html>\n<html lang="en"><head><meta charset="utf-8">\n'
            '<title>Diwan Database API %s</title>\n'
            '<link rel="canonical" href="%s">\n'
            '<meta http-equiv="refresh" content="0; url=%s">\n</head>\n'
            '<body><p>Redirecting to <a href="%s">%s</a>.</p></body></html>\n'
            % (label, url, url, url, dest))
    with open(os.path.join(folder, "index.html"), "w", encoding="utf-8") as f:
        f.write(html)


if releases:
    alias(api, releases[0], "latest")
    for major in {key(v)[0] for v in releases}:
        newest = next(v for v in releases if key(v)[0] == major)
        alias(os.path.join(api, str(major)), newest, str(major))
    for major, minor in {key(v)[:2] for v in releases}:
        newest = next(v for v in releases if key(v)[:2] == (major, minor))
        alias(os.path.join(api, "%d.%d" % (major, minor)), newest, "%d.%d" % (major, minor))
PY
echo "published $target to $api/$target"
