#!/usr/bin/env python3
"""Build a ref-dynamic dossier of Collie's fork provenance and entanglement."""

import argparse
import base64
import copy
from collections import Counter
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


UPSTREAMS = {
    "signalapp/Signal-Android": "signal",
    "mollyim/mollyim-android": "molly",
}
PROVENANCE_CLASSES = ("collie-original", "collie-modified", "upgrade-seam", "molly-inherited", "signal-inherited")
REPO = Path(__file__).resolve().parents[2]
MAX_PART_BYTES = 180_000
MAX_COMMENT_BYTES = 60_000
DOMAIN_HINTS = (
    ("D1", "Push, relay, and UnifiedPush", ("unifiedpush", "push", "relay", "mollysocket", "ntfy")),
    ("D2", "Lock, recovery, registration, and linked devices", ("lock", "recovery", "registration", "linkdevice", "linkeddevice", "linked-device", "auth", "identity")),
    ("D3", "Backup, restore, keys, and storage", ("backup", "restore", "key", "database", "storage")),
    ("D4", "Messaging, group admission, attachments, calls, and logging", ("message", "conversation", "group", "attachment", "webrtc", "call", "logging", "log/")),
    ("D5", "Manifest, IPC, WebView, network, updater, GMS, donations, and build", ("manifest", "ipc", "webview", "network", "updat", "playservices", "donation", "build.gradle", "gradle", "gms")),
    ("D6", "Signal and Molly merge seams", ("",)),
)


def git(*args):
    return subprocess.run(["git", *map(str, args)], cwd=REPO, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def resolve(ref):
    return git("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").decode().strip()


def parents(commit):
    return git("show", "-s", "--format=%P", commit).decode().split()


def upstream_merges(head):
    # Upgrades commonly arrive inside a PR merge. Walk every reachable merge,
    # not just the first-parent release branch.
    raw = git("log", "--merges", "--date-order", "--format=%H%x00%P%x00%B%x00", head).decode(errors="replace").split("\0")
    found = []
    for oid, parent_text, message in zip(raw[0::3], raw[1::3], raw[2::3]):
        oid, parent_text, message = oid.strip(), parent_text.strip(), message.strip()
        ps = parent_text.split()
        if len(ps) != 2:
            continue
        repo = next((r for r in UPSTREAMS if re.search(rf"^Upstream: {re.escape(r)} ", message, re.M)), None)
        if repo:
            m = re.search(rf"^Upstream: {re.escape(repo)} (.+)$", message, re.M)
            found.append({"commit": oid, "parent": ps[0], "upstream_parent": ps[1], "repository": repo,
                          "kind": UPSTREAMS[repo], "ref": m.group(1).strip() if m else ""})
    return found


def paths_between(a, b):
    raw = git("diff", "--no-ext-diff", "--no-textconv", "--name-only", "-z", "--no-renames", a, b, "--")
    return sorted({path.decode("utf-8", errors="surrogateescape") for path in raw.split(b"\0") if path})


def conflict_paths(section):
    """Parse conflict-list entries, preserving spaces and removing status suffixes."""
    result = set()
    statuses = ("both added", "both modified", "both deleted", "both renamed",
                "added by us", "added by them", "deleted by us", "deleted by them",
                "modified by us", "modified by them")
    for line in section.split("\n"):
        if line.startswith(("- ", "* ")):
            path = line[2:]
        else:
            continue
        for status in statuses:
            suffix = f" ({status})"
            if path.endswith(suffix):
                path = path[:-len(suffix)]
                break
        if path:
            result.add(path)
    return result


def diff_text(a, b, paths=None, attr_source=None):
    if paths == []:
        return ""
    args = ([f"--attr-source={attr_source}"] if attr_source else [])
    args.extend(["--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--unified=3", a, b, "--"])
    if paths is not None:
        args.extend(paths)
    return git(*args).decode(errors="replace")


def diff_numstat(a, b, attr_source=None):
    """Return per-path added/deleted counts from Git's NUL-delimited output."""
    args = ([f"--attr-source={attr_source}"] if attr_source else [])
    raw = git(*args, "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--no-renames",
              "--numstat", "-z", a, b, "--")
    result = {}
    for record in raw.split(b"\0"):
        if not record:
            continue
        added, deleted, path = record.split(b"\t", 2)
        if added == b"-" or deleted == b"-":  # binary file
            counts = (0, 0)
        else:
            counts = (int(added), int(deleted))
        result[path.decode("utf-8", errors="surrogateescape")] = counts
    return result


def diff_hunk_metadata(a, b, paths, attr_source=None):
    """Attribute hunks to exact tree paths, excluding descendants of replaced files."""
    counts, spans = Counter(), {}
    # Git uses checkout attributes by default; callers can pin another source.
    # A literal pathspec can select descendants when this path is a directory
    # on one side, so accept only a matching file header.
    for path in paths:
        path_bytes = os.fsencode(path)
        args = ([f"--attr-source={attr_source}"] if attr_source else [])
        args.extend(["-c", "core.quotePath=true", "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv",
                     "--no-renames", "--default-prefix", "--unified=3", a, b, "--", path])
        patch = subprocess.run(
            ["git", *args], cwd=REPO, check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
        raw_header = b"diff --git a/" + path_bytes + b" b/" + path_bytes
        quoted_old = _git_quote_path(b"a/" + path_bytes)
        quoted_new = _git_quote_path(b"b/" + path_bytes)
        quoted_header = b"diff --git " + quoted_old + b" " + quoted_new
        section_path = None
        for line in patch.split(b"\n"):
            if line.startswith(b"diff --git "):
                section_path = path if line in (raw_header, quoted_header) else None
            elif section_path == path and line.startswith(b"@@"):
                counts[path] += 1
                match = re.match(rb"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
                if match:
                    spans.setdefault(path, []).append({"old_start": int(match[1]), "old_count": int(match[2] or 1),
                                                       "new_start": int(match[3]), "new_count": int(match[4] or 1)})
    return sum(counts.values()), counts, spans


def _git_quote_path(path):
    """Return Git's core.quotePath=true C-style spelling for a path."""
    escapes = {7: b"\\a", 8: b"\\b", 9: b"\\t", 10: b"\\n", 11: b"\\v", 12: b"\\f", 13: b"\\r",
               34: b'\\"', 92: b"\\\\"}
    if not any(byte < 32 or byte >= 127 or byte in (34, 92) for byte in path):
        return path
    quoted = bytearray(b'"')
    for byte in path:
        if byte in escapes:
            quoted.extend(escapes[byte])
        elif byte < 32 or byte >= 127:
            quoted.extend(f"\\{byte:03o}".encode("ascii"))
        else:
            quoted.append(byte)
    quoted.append(34)
    return bytes(quoted)


def display_text(value):
    """Make arbitrary Git path bytes safe for UTF-8 JSON and Markdown output."""
    return "".join(f"\\x{ord(char) - 0xdc00:02x}" if 0xdc80 <= ord(char) <= 0xdcff else
                   "\\\\" if char == "\\" else char for char in str(value))


def json_safe(value):
    """Replace surrogateescaped path bytes before writing user-facing JSON."""
    if isinstance(value, str):
        return display_text(value)
    if isinstance(value, list):
        return [json_safe(item) for item in value]
    if isinstance(value, tuple):
        return [json_safe(item) for item in value]
    if isinstance(value, dict):
        return {json_safe(key): json_safe(item) for key, item in value.items()}
    return value


def domain(path):
    low = path.lower()
    for key, title, words in DOMAIN_HINTS[:-1]:
        if any(word.lower() in low for word in words):
            return key, title
    return DOMAIN_HINTS[-1][0:2]


def runtime_path(path):
    low = path.lower()
    if (low.startswith(("docs/", "website/", "fastlane/")) or "/drawable" in low or
            "/assets/" in low or re.search(r"(^|/)res/values-[^/]+/", low) or
            re.search(r"(^|/)res/(drawable[^/]*|mipmap[^/]*)/", low)):
        return False
    return bool(re.search(r"\.(java|kt|xml|gradle|kts|pro|json|properties|proto)$", low))


def tree_blobs(commit):
    output = git("ls-tree", "-r", "-z", "--full-tree", commit)
    result = {}
    for record in output.split(b"\0"):
        if not record:
            continue
        metadata, path = record.split(b"\t", 1)
        fields = metadata.split()
        if len(fields) == 3 and fields[1] == b"blob":
            result[path.decode("utf-8", errors="surrogateescape")] = fields[2].decode("ascii")
    return result


def provenance(path, head_tree, upstream_trees, seam_paths=None):
    """Classify a head file against the actual upstream snapshots and merge evidence."""
    current = head_tree.get(path)
    present = {kind: tree.get(path) for kind, tree in upstream_trees.items()}
    present = {kind: blob for kind, blob in present.items() if blob is not None}
    if not present:
        return "collie-original"
    # Conflict evidence is specific to the merge result, so it takes priority
    # over matching either upstream snapshot.
    if current is not None and current in (seam_paths or {}).get(path, set()):
        return "upgrade-seam"
    if ("molly" in present and current == present["molly"] and
            current != present.get("signal")):
        return "molly-inherited"
    if "signal" in present and current == present["signal"]:
        return "signal-inherited"
    return "collie-modified"


def ref_text(head, path):
    """Read a tracked file from the requested commit, never the checkout."""
    try:
        return git("show", f"{head}:{path}").decode("utf-8", errors="replace")
    except subprocess.CalledProcessError:
        return None


def latest_upstreams(merges):
    result = {}
    for merge in merges:
        result.setdefault(merge["kind"], merge["upstream_parent"])
    return result


def feature_matches(parts, pattern):
    """Match feature_diff.py's component-aware patterns (including **)."""
    segments = pattern.split("/")
    def visit(path_parts, index):
        if index == len(segments):
            return not path_parts
        segment = segments[index]
        if segment == "**":
            if index == len(segments) - 1:
                return True
            return any(visit(path_parts[offset:], index + 1) for offset in range(len(path_parts) + 1))
        return bool(path_parts and fnmatch.fnmatchcase(path_parts[0], segment) and visit(path_parts[1:], index + 1))
    return visit(parts, 0)


def drift_paths(changed, signal_tree, molly_tree, head_tree, merge_trees=()):
    """Return local edits on paths whose Signal and Molly source blobs differ."""
    result = []
    for path in changed:
        signal, molly, current = signal_tree.get(path), molly_tree.get(path), head_tree.get(path)
        if signal == molly or (current is not None and current in (signal, molly)):
            continue
        # A merge result that is still present at head is imported integration
        # output, not a later Collie edit to the upstream divergence.
        if current is not None and any(tree.get(path) == current for tree in merge_trees):
            continue
        result.append(path)
    return sorted(result)


def chunks(text, limit=None):
    limit = limit or MAX_PART_BYTES
    lines, part, size = text.splitlines(keepends=True), [], 0
    result = []
    for line in lines:
        n = len(line.encode("utf-8", errors="replace"))
        if n > limit:
            if part:
                result.append("".join(part)); part, size = [], 0
            fragment, fragment_size = [], 0
            for char in line:
                char_size = len(char.encode("utf-8", errors="replace"))
                if fragment and fragment_size + char_size > limit:
                    result.append("".join(fragment)); fragment, fragment_size = [], 0
                fragment.append(char); fragment_size += char_size
            if fragment:
                result.append("".join(fragment))
            continue
        if part and size + n > limit:
            result.append("".join(part)); part, size = [], 0
        part.append(line); size += n
    if part or not result:
        result.append("".join(part))
    return result


def write_text_parts(out, stem, text, limit):
    """Write text in bounded parts and return their names."""
    parts = chunks(text, limit)
    names = []
    for i, part in enumerate(parts, 1):
        name = f"{stem}-{i:03}.md" if len(parts) > 1 else f"{stem}.md"
        (out / name).write_text(part)
        names.append(name)
    return names


def write_json_parts(out, stem, rows, limit):
    """Write complete JSON rows in bounded arrays and a compact pointer file."""
    encoded = json.dumps(json_safe(rows), ensure_ascii=True, indent=2) + "\n"
    if len(encoded.encode("utf-8")) <= limit:
        name = f"{stem}.json"
        (out / name).write_text(encoded)
        return name, [name]
    parts, current = [], []
    for row in rows:
        candidate = current + [row]
        rendered = json.dumps(json_safe(candidate), ensure_ascii=True, indent=2) + "\n"
        if current and len(rendered.encode("utf-8")) > limit:
            parts.append(current)
            current = [row]
        else:
            current = candidate
    if current:
        parts.append(current)
    names = []
    for i, part in enumerate(parts, 1):
        name = f"{stem}-{i:03}.json"
        rendered = json.dumps(json_safe(part), ensure_ascii=True, indent=2) + "\n"
        # Compact before shortening: pretty-printing can be the only reason a
        # complete record exceeds the byte limit.
        compact = json.dumps(json_safe(part), ensure_ascii=True, separators=(",", ":")) + "\n"
        if len(compact.encode("ascii")) <= limit:
            rendered = compact
        elif len(part) == 1:
            # ASCII escaping can expand CJK text sixfold, so measure the
            # serialized representation after every shortening step.
            compact_rows = [_fit_json_record(part[0], max(1, limit - 3))]
            rendered = json.dumps(json_safe(compact_rows), ensure_ascii=True, separators=(",", ":")) + "\n"
        else:
            compact_rows = [_fit_json_record(row, max(1, limit - 3)) for row in part]
            rendered = json.dumps(json_safe(compact_rows), ensure_ascii=True, separators=(",", ":")) + "\n"
        if len(rendered.encode("utf-8")) > limit:
            raise ValueError(f"JSON record in {stem} cannot fit the {limit}-byte part limit")
        (out / name).write_text(rendered)
        names.append(name)
    pointer = f"{stem}.json"
    (out / pointer).write_text(json.dumps({"count": len(rows), "parts": names}, separators=(",", ":")) + "\n")
    return pointer, names


def _fit_json_record(row, budget):
    """Shorten string values as needed to fit one valid record in a JSON part."""
    result = json_safe(row)
    def encoded_size():
        return len(json.dumps([result], ensure_ascii=True, separators=(",", ":")).encode("utf-8")) + 1
    while encoded_size() > budget:
        candidates = []
        def collect(value, path):
            if isinstance(value, dict):
                for child_key, child in value.items(): collect(child, path + [child_key])
            elif isinstance(value, list):
                for index, child in enumerate(value): collect(child, path + [index])
            elif isinstance(value, str) and len(value) > 0:
                marker = "…"
                reduced = value[: max(0, len(value) // 2)] + marker
                if reduced != value:
                    # Evaluate candidate byte reductions using an isolated copy.
                    candidate_result = copy.deepcopy(result)
                    if path:
                        candidate_parent = candidate_result
                        for item in path[:-1]:
                            candidate_parent = candidate_parent[item]
                        candidate_parent[path[-1]] = reduced
                    else:
                        candidate_result = reduced
                    candidate_size = len(json.dumps([candidate_result], ensure_ascii=True,
                                                    separators=(",", ":")).encode("ascii")) + 1
                    reduction = encoded_size() - candidate_size
                    if reduction > 0:
                        candidates.append((reduction, path, reduced))
        collect(result, [])
        if not candidates:
            raise ValueError("JSON record cannot be shortened enough to fit the byte limit")
        _, path, reduced = max(candidates, key=lambda item: item[0])
        previous_size = encoded_size()
        if path:
            parent = result
            for item in path[:-1]:
                parent = parent[item]
            parent[path[-1]] = reduced
        else:
            result = reduced
        if encoded_size() >= previous_size:
            raise ValueError("JSON record cannot be shortened enough to fit the byte limit")
    return result


def dossiers(head_ref, out, since_ref=None, max_kb=150):
    head = resolve(head_ref)
    discovered_merges = upstream_merges(head)
    since = resolve(since_ref) if since_ref else None
    if since:
        incremental = set(git("rev-list", f"{since}..{head}").decode().splitlines())
        merges = [m for m in discovered_merges if m["commit"] in incremental]
    else:
        merges = discovered_merges
    max_bytes = max(1, int(max_kb * 1024))
    out.mkdir(parents=True, exist_ok=True)
    # Refresh only artifacts owned by this generator so a repeated run cannot
    # leave stale dossier files from an older schema in the output folder.
    for path in out.iterdir():
        if path.is_file() and (re.fullmatch(r"d[1-6]\.md", path.name) or
                re.fullmatch(r"d[1-6](-.*)?\.diff", path.name) or
                re.fullmatch(r"d[1-6](?:-files(?:-\d+)?|-\d+)\.json", path.name) or
                re.fullmatch(r"d[1-6](?:-\d+)\.md", path.name) or
                re.fullmatch(r"files-\d+\.jsonl", path.name) or
                re.fullmatch(r"security-leads(?:-\d+)?\.json", path.name) or
                re.fullmatch(r"index(?:-\d+)?\.json", path.name) or
                path.name in {"INDEX.md", "index.json", "security-leads.json", "manifest-and-build-delta.md"}):
            path.unlink()
    domains = {key: {"id": key, "title": title, "files": [], "diff_parts": []} for key, title, _ in DOMAIN_HINTS}
    manifest = []
    excluded_noise = 0
    upstream_refs = latest_upstreams(discovered_merges)
    upstream_trees = {kind: tree_blobs(ref) for kind, ref in upstream_refs.items()}
    head_tree = tree_blobs(head)
    empty_tree = subprocess.run(["git", "hash-object", "-t", "tree", "--stdin"], cwd=REPO,
                                input=b"", check=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE).stdout.decode().strip()
    if since:
        candidate_paths = set(paths_between(since, head))
    else:
        candidate_paths = set()
        for ref in upstream_refs.values():
            candidate_paths.update(paths_between(ref, head))
        # Keep explicit evidence for files resolved at the most recent merge seam.
        for kind, ref in upstream_refs.items():
            merge = next(m for m in discovered_merges if m["kind"] == kind)
            candidate_paths.update(paths_between(merge["parent"], merge["commit"]))
    seam_paths = {}
    for merge in discovered_merges:
        message = git("show", "-s", "--format=%B", merge["commit"]).decode(errors="replace")
        section = re.search(r"Conflicts at merge time:\s*(.*?)(?:\n\n|\Z)", message, re.S)
        if section:
            merge_tree = tree_blobs(merge["commit"])
            for conflict_path in conflict_paths(section.group(1)):
                if merge_tree.get(conflict_path) is not None:
                    seam_paths.setdefault(conflict_path, set()).add(merge_tree[conflict_path])
    for path in sorted(candidate_paths):
        if not runtime_path(path):
            excluded_noise += 1
            continue
        classification = provenance(path, head_tree, upstream_trees, seam_paths)
        # A plain Signal import is upstream content, not a fork delta. Keep
        # Molly-only content and recorded conflict resolutions as evidence.
        if classification == "signal-inherited":
            continue
        key, _ = domain(path)
        if classification == "upgrade-seam":
            key = "D6"
        context = ",".join(f"{kind}:{ref}" for kind, ref in upstream_refs.items())
        record = {"path": path, "merge": "", "upstream": context, "parent": "", "comparison": "head-vs-upstream",
                  "provenance": classification}
        domains[key]["files"].append(record)
        manifest.append(record)
        wrote_diff = False
        for kind, ref in upstream_refs.items():
            if upstream_trees[kind].get(path) is None:
                continue
            text = diff_text(ref, head, [path])
            if not text:
                merge = next((m for m in discovered_merges if m["kind"] == kind and
                              path in paths_between(m["parent"], m["commit"])), None)
                if merge:
                    text = diff_text(merge["parent"], head, [path])
            if not text:
                continue
            pieces = chunks(text, max_bytes)
            for i, part in enumerate(pieces, 1):
                name = f"{key.lower()}-{kind}-{hashlib.sha1(path.encode('utf-8', errors='surrogateescape')).hexdigest()[:10]}-{i:03}.diff"
                (out / name).write_text(part)
                domains[key]["diff_parts"].append(name)
                wrote_diff = True
        if classification == "collie-original" and not wrote_diff:
            source = since or empty_tree
            text = diff_text(source, head, [path])
            if text:
                for i, part in enumerate(chunks(text, max_bytes), 1):
                    name = f"{key.lower()}-original-{hashlib.sha1(path.encode('utf-8', errors='surrogateescape')).hexdigest()[:10]}-{i:03}.diff"
                    (out / name).write_text(part)
                    domains[key]["diff_parts"].append(name)
    security = security_leads(head, merges)
    for key, entry in domains.items():
        lines = [f"# {key}: {entry['title']}", "", f"Head: `{head}`", "", f"Since: `{since or 'latest upstream baseline'}`", "",
                 "Current runtime files are compared with the latest discovered Signal and Molly trees. `upgrade-seam` includes paths identified in recorded merge conflict messages.", ""]
        lines += [f"- `{inline_code(row['path'])}` — {row['provenance']}" for row in entry["files"]]
        lines += ["", "#### Diff parts", "", *[f"- [{name}]({name})" for name in entry["diff_parts"]], ""]
        stem = key.lower()
        body = "\n".join(lines) + "\n"
        markdown_parts = write_text_parts(out, stem, body, max_bytes)
        if len(markdown_parts) > 1:
            overview = [f"# {key}: {entry['title']}", "", f"Head: `{head}`", f"Since: `{since or 'latest upstream baseline'}`", "",
                        "Dossier content is split into bounded parts:", ""]
            overview += [f"- [{name}]({name})" for name in markdown_parts]
            (out / f"{stem}.md").write_text("\n".join(overview) + "\n")
        manifest_pointer, manifest_parts = write_json_parts(out, f"{stem}-files", entry["files"], max_bytes)
        entry["markdown_parts"] = markdown_parts
        entry["manifest"] = manifest_pointer
        entry["manifest_parts"] = manifest_parts
        entry["file_count"] = len(entry["files"])
        entry["diff_part_count"] = len(entry["diff_parts"])
        del entry["files"]
        del entry["diff_parts"]
    security_pointer, security_parts = write_json_parts(out, "security-leads", security, max_bytes)
    delta = manifest_delta(head, merges)
    (out / "manifest-and-build-delta.md").write_text(delta)
    observed_provenance = Counter(row["provenance"] for row in manifest)
    provenance_counts = {name: observed_provenance[name] for name in PROVENANCE_CLASSES}
    index = {"head": head, "since": since, "upstream_merges": merges, "domains": domains,
             "security_leads": len(security), "security_lead_manifest": security_pointer, "security_lead_parts": security_parts,
             "runtime_file_rows": len(manifest), "excluded_non_runtime_rows": excluded_noise,
             "provenance_counts": dict(provenance_counts), "bounded_part_bytes": max_bytes}
    index_text = json.dumps(json_safe(index), indent=2, ensure_ascii=True) + "\n"
    if len(index_text.encode("utf-8")) <= max_bytes:
        (out / "index.json").write_text(index_text)
    else:
        compact = {key: value for key, value in index.items() if key not in ("domains", "upstream_merges")}
        part_base = {**compact, "domains": {}, "upstream_merges": []}
        groups = []
        current = {"domains": {}, "upstream_merges": []}
        entries = [("domains", key, value) for key, value in domains.items()]
        entries += [("upstream_merges", str(i), merge) for i, merge in enumerate(merges)]
        for section, key, value in entries:
            candidate = {**current, section: {**current[section], key: value} if section == "domains" else current[section] + [value]}
            if (current["domains"] or current["upstream_merges"]) and len((json.dumps({**part_base, **candidate}, separators=(",", ":"), ensure_ascii=True) + "\n").encode()) > max_bytes:
                groups.append(current)
                current = {"domains": {}, "upstream_merges": []}
                candidate = {**current, section: {key: value} if section == "domains" else [value]}
            current = candidate
        if current["domains"] or current["upstream_merges"]:
            groups.append(current)
        names = []
        for i, group in enumerate(groups, 1):
            name = f"index-{i:03}.json"
            (out / name).write_text(json.dumps({**part_base, **group}, separators=(",", ":"), ensure_ascii=True) + "\n")
            names.append(name)
        (out / "index.json").write_text(json.dumps({**compact, "parts": names}, separators=(",", ":")) + "\n")
    md = ["# Fork security review dossiers", "", f"Head: `{head}`", f"Since: `{since or 'all history'}`", "", "| Domain | Runtime files | Diff parts |", "|---|---:|---:|"]
    for key, entry in domains.items():
        md.append(f"| [{key}: {entry['title']}]({key.lower()}.md) | {entry['file_count']} | {entry['diff_part_count']} |")
    md += ["", f"See [manifest and build delta](manifest-and-build-delta.md) and [security leads]({security_pointer})."]
    (out / "INDEX.md").write_text("\n".join(md) + "\n")
    return index


def manifest_delta(head, merges):
    changed_by_merge = {m["commit"]: paths_between(m["parent"], m["commit"]) for m in merges}
    paths = sorted({p for changed in changed_by_merge.values() for p in changed
                    if p.endswith(("AndroidManifest.xml", "build.gradle", "build.gradle.kts", "gradle.properties", "libs.versions.toml"))})
    rows = ["# Manifest and build delta", "", f"Head: `{head}`", "", "| Path | Upstream merge |", "|---|---|"]
    rows += [f"| {inline_code(p)} | `{m['commit'][:12]}` |" for m in merges for p in paths if p in changed_by_merge[m["commit"]]]
    return "\n".join(rows + (["", "No manifest or build files changed in discovered upgrades."] if not paths else ["", f"{len(paths)} distinct manifest/build paths."])) + "\n"


SECURITY_RE = re.compile(r"(trust|credential|secret|token|certificate|crypto|encrypt|decrypt|permission|exported|webview|sql(raw)?|deep.?link|intent|auth|pin|backup|keystore)", re.I)


def security_leads(head, merges):
    # Candidate discovery is content driven and scoped to fork merge diffs; no claims of vulnerability are made.
    result = []
    for merge in merges:
        additions = diff_numstat(merge["parent"], merge["commit"])
        for path in paths_between(merge["parent"], merge["commit"]):
            if not re.search(r"\.(java|kt|xml|gradle|pro|json)$", path, re.I):
                continue
            if additions.get(path, (0, 0))[0] == 0:
                continue
            patch = diff_text(merge["parent"], merge["commit"], [path])
            matches = [(line.strip()[:180], SECURITY_RE.search(line).group(0).lower())
                       for line in added_patch_lines(patch) if SECURITY_RE.search(line)]
            for evidence, rule in matches[:30]:
                result.append({"kind": "upstream-added-code-match", "path": path, "merge": merge["commit"],
                               "upstream": merge["repository"], "rule": rule, "evidence": evidence,
                               "truncated": len(matches) > 30})
    # Repository-maintained reviews and protocol pins are evidence leads, not findings.
    head_files = tree_blobs(head)
    reviewed_path = ".github/codeql/reviewed-findings.json"
    reviewed_text = ref_text(head, reviewed_path) if reviewed_path in head_files else None
    if reviewed_text is not None:
        try:
            data = json.loads(reviewed_text)
            for finding in data.get("reviews", []):
                result.append({"source": reviewed_path, "kind": "reviewed-codeql",
                               "id": finding.get("id"), "rule": finding.get("rule"),
                               "disposition": finding.get("disposition"), "path": finding.get("location", {}).get("uri")})
        except (ValueError, OSError):
            pass
    preflight_path = "tooling/ci/preflight.py"
    preflight_text = ref_text(head, preflight_path) if preflight_path in head_files else None
    if preflight_text is not None:
        for number, line in enumerate(preflight_text.splitlines(), 1):
            if re.search(r"CollieSocket|colliesocket|contract", line, re.I):
                result.append({"source": preflight_path, "kind": "colliesocket-pin", "line": number, "text": line.strip()[:240]})
    fuzz_prefix = "tooling/fuzz-encrypted-push/"
    for path in sorted(name for name in head_files if name.startswith(fuzz_prefix)):
        if Path(path).suffix.lower() not in (".py", ".kt", ".md", ".json", ".toml"):
            continue
        raw = git("cat-file", "-s", f"{head}:{path}").decode().strip()
        if not raw.isdigit() or int(raw) >= 500_000:
            continue
        content = ref_text(head, path)
        if content is not None:
            for number, line in enumerate(content.splitlines(), 1):
                if re.search(r"contract.{0,20}(version|v[0-9])|version.{0,20}contract", line, re.I):
                    result.append({"source": path, "kind": "contract-version", "line": number, "text": line.strip()[:240]})
    return result


def added_patch_lines(patch):
    """Return additions without treating unified-diff file headers as content."""
    result, in_hunk = [], False
    for line in patch.split("\n"):
        if line.startswith("diff --git "):
            in_hunk = False
        elif line.startswith("@@"):
            in_hunk = True
        elif in_hunk and line.startswith("+"):
            result.append(line[1:])
    return result


def entanglement(base_ref, head_ref, history_ref=None):
    head = resolve(head_ref)
    history = resolve(history_ref) if history_ref else head
    merges = upstream_merges(history)
    if base_ref:
        base = resolve(base_ref)
    elif merges:
        base = resolve(merges[0]["upstream_parent"])
    else:
        base = git("rev-list", "--max-parents=0", head).decode().splitlines()[-1]
    upgrade_rows, hunk_scatter = [], []
    for merge in merges:
        first = paths_between(merge["parent"], merge["commit"])
        second = paths_between(merge["upstream_parent"], merge["commit"])
        # Files changed from both parents are conflict-resolution / integration hotspots, not proof of a textual conflict.
        overlap = sorted(set(first) & set(second))
        numstat = diff_numstat(merge["upstream_parent"], merge["commit"], attr_source=history if history_ref else None)
        hunks, path_hunks, spans = diff_hunk_metadata(merge["upstream_parent"], merge["commit"], second,
                                                      attr_source=history if history_ref else None)
        added_lines = 0
        for path in second:
            added_lines += numstat.get(path, (0, 0))[0]
        row = {"commit": merge["commit"], "kind": merge["kind"], "ref": merge["ref"], "first_parent_files": len(first),
               "upstream_files": len(second), "shared_files": len(overlap), "hunks": hunks, "added_lines": added_lines,
               "hunk_scatter": dict(path_hunks.most_common()), "hunk_spans": spans, "shared_paths": overlap, "upstream_paths": second}
        upgrade_rows.append(row)
        hunk_scatter.append({"commit": merge["commit"], "files": len(second), "hunks": hunks, "spans": spans})
    delta_base = git("merge-base", base, head).decode().strip()
    changed = paths_between(delta_base, head)
    layer_paths = {}
    commits = git("rev-list", "--no-merges", f"{base}..{head}").decode().splitlines()
    for commit in commits:
        layer = git("show", "-s", "--format=%(trailers:key=Layer,valueonly)", commit).decode().strip()
        if not layer:
            continue
        for path in paths_between(f"{commit}^", commit):
            layer_paths.setdefault(path, set()).add(layer)
    feature_collisions = {path: sorted(layers) for path, layers in layer_paths.items()
                          if path in changed and len(layers) > 2}
    try:
        catalog_ref = history if history_ref else head
        catalog = tomllib.loads(ref_text(catalog_ref, "tooling/features.toml") or "")
        feature_paths = {}
        for name, spec in catalog.get("features", {}).items():
            patterns = spec.get("paths", []) + spec.get("shared_paths", [])
            for path in changed:
                if any(feature_matches(path.split("/"), pattern) for pattern in patterns):
                    feature_paths.setdefault(path, set()).add(name)
        feature_collisions = {p: sorted(names) for p, names in feature_paths.items() if len(names) > 2}
    except (OSError, ValueError, KeyError):
        pass
    latest_refs = latest_upstreams(merges)
    if "signal" in latest_refs and "molly" in latest_refs:
        signal_tree, molly_tree = tree_blobs(latest_refs["signal"]), tree_blobs(latest_refs["molly"])
        merge_trees = [tree_blobs(merge["commit"]) for merge in merges]
        drift = drift_paths(changed, signal_tree, molly_tree, tree_blobs(head), merge_trees)
    else:
        drift = []
    code_added = strings_added = 0
    for path, (added, _deleted) in diff_numstat(delta_base, head, attr_source=history if history_ref else None).items():
        if is_string_path(path):
            strings_added += added
        else:
            code_added += added
    conflicts = Counter()
    for merge in merges:
        message = git("show", "-s", "--format=%B", merge["commit"]).decode(errors="replace")
        section = re.search(r"Conflicts at merge time:\s*(.*?)(?:\n\n|\Z)", message, re.S)
        paths = conflict_paths(section.group(1)) if section else set()
        if not paths:
            continue
        conflicts.update(paths)
        # Count a follow-up only when a later resolution/fix commit actually edits the conflicted path.
        followups = git("rev-list", "--first-parent", f"{merge['commit']}..{history}").decode().splitlines()
        for commit in followups:
            message = git("show", "-s", "--format=%s%n%b", commit).decode(errors="replace")
            if not re.search(r"(fix|resolve|conflict)", message, re.I):
                continue
            conflicts.update(set(paths_between(f"{commit}^", commit)) & paths)
    return {"base": base, "delta_base": delta_base, "head": head, "upgrade_conflict_recurrence": [{"path": p, "count": n} for p, n in conflicts.most_common()],
             "upgrade_hunk_scatter": hunk_scatter, "upgrade_merges": upgrade_rows,
            "feature_collisions": [{"path": p, "layers": labels} for p, labels in sorted(feature_collisions.items())],
             "drift_intersection": drift, "added_code_to_localized_string_ratio": {"code": code_added, "localized_strings": strings_added,
                    "ratio": round(code_added / strings_added, 3) if strings_added else None}}


def is_string_path(path):
    return bool(re.search(r"(?:^|/)res/values-[^/]+/strings\.xml$", path, re.I))


def markdown(report):
    ratio = report["added_code_to_localized_string_ratio"]
    ratios = f"{ratio['code']} code / {ratio['localized_strings']} localized string lines" + (f" (ratio {ratio['ratio']})" if ratio["ratio"] is not None else " (no localized string lines)")
    lines = ["## Fork entanglement", "", f"Base: `{report['base']}`", f"Head: `{report['head']}`", "",
             f"Added-line mix: {ratios}.", "", "### Upgrade hunk scatter", "", "| Upgrade | Changed files | Hunks | Shared parent paths |", "|---|---:|---:|---:|"]
    for row in report["upgrade_hunk_scatter"]:
        lines.append(f"| `{row['commit'][:12]}` | {row['files']} | {row['hunks']} | {next((m['shared_files'] for m in report['upgrade_merges'] if m['commit']==row['commit']), 0)} |")
    lines += ["", "### Repeated integration paths", ""]
    repeated = [r for r in report["upgrade_conflict_recurrence"] if r["count"] > 1]
    lines += [f"- {inline_code(r['path'])} ({r['count']} recorded conflicts or follow-up fixes)" for r in repeated[:30]] or ["- None found."]
    lines += ["", "### Feature collisions", ""]
    lines += [f"- {inline_code(row['path'])}: {', '.join(row['layers'])}" for row in report["feature_collisions"][:30]] or ["- None found."]
    lines += ["", f"### Changed paths overlapping upstream integration: {len(report['drift_intersection'])}", ""]
    lines += [f"- {inline_code(p)}" for p in report["drift_intersection"][:50]] or ["- None."]
    return "\n".join(lines) + "\n"


def pr_markdown(report):
    base, head = report["base"], report["head"]
    merges = report["upgrade_merges"]
    refs = latest_upstreams(upstream_merges(base))
    upstream_owned = set().union(*(set(tree_blobs(ref)) for ref in refs.values())) if refs else set()
    delta_base = report.get("delta_base") or base
    touched = sorted(upstream_owned & set(paths_between(delta_base, head)))
    injected_hunks, injected_lines = upstream_delta_stats(delta_base, head, touched, attr_source=base)
    repeated = {r["path"] for r in report["upgrade_conflict_recurrence"] if r["count"] > 1}
    hotspots = sorted(repeated & set(touched))
    domains = Counter(domain(p)[0] for p in touched if runtime_path(p))
    string_edits = [p for p in paths_between(delta_base, head) if is_string_path(p) and p in touched]
    lines = ["## Fork footprint delta", "", f"Base: {inline_code(base)} · Head: {inline_code(head)}", "",
             f"- Upstream files touched: **{len(touched)}**; new hunks in those files: **{injected_hunks}**, added lines: **{injected_lines}**.",
             f"- Historical repeat-conflict hotspots hit: **{len(hotspots)}**.",
             f"- Inline edits to upstream localized strings: **{len(string_edits)}**.",
             f"- Molly-vs-Signal drift overlaps: **{len(report['drift_intersection'])}**.",
             "- Affected security domains: " + (", ".join(f"{k} ({v})" for k, v in sorted(domains.items())) or "none identified") + ".", ""]
    if hotspots:
        lines.append("Repeated hotspots: " + ", ".join(inline_code(p) for p in hotspots[:12]) + ".")
    if string_edits:
        lines.append("Localized strings: " + ", ".join(inline_code(p) for p in string_edits[:12]) + ".")
    return "\n".join(lines).rstrip() + "\n"


def upstream_delta_stats(base, head, exact_paths, attr_source=None):
    """Count only exact upstream-owned files, using Git's path-aware metadata."""
    if not exact_paths:
        return 0, 0
    hunks, _path_hunks, _spans = diff_hunk_metadata(base, head, exact_paths, attr_source=attr_source)
    numstat = diff_numstat(base, head, attr_source=attr_source)
    added_lines = sum(numstat.get(path, (0, 0))[0] for path in exact_paths)
    return hunks, added_lines


def inline_code(value, limit=240):
    """Render untrusted text as one bounded Markdown code span."""
    text = "".join(" " if ord(char) < 32 or ord(char) == 127 else char for char in display_text(value))
    if len(text) > limit:
        text = text[:limit - 1] + "…"
    delimiter = "`" * (max((len(match.group(0)) for match in re.finditer(r"`+", text)), default=0) + 1)
    padding = " " if text.startswith(("`", " ")) or text.endswith(("`", " ")) else ""
    return f"{delimiter}{padding}{text}{padding}{delimiter}"


def bounded_comment(body):
    """Keep a comment below GitHub's body limit, including its sticky marker."""
    marker = "<!-- collie-fork-footprint -->\n"
    suffix = "\n_Report shortened to fit the GitHub comment size limit._\n"
    if body.startswith(marker):
        body = body[len(marker):]
    available = MAX_COMMENT_BYTES - len((marker + suffix).encode("utf-8"))
    kept, size = [], 0
    for line in body.splitlines(keepends=True):
        encoded_size = len(line.encode("utf-8"))
        if size + encoded_size > available:
            break
        kept.append(line)
        size += encoded_size
    if len(kept) == len(body.splitlines(keepends=True)):
        return marker + body
    return marker + "".join(kept).rstrip() + suffix


class GitHubApiRedirectHandler(HTTPRedirectHandler):
    """Allow API redirects only while they remain on the GitHub API origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urlsplit(newurl)
        if (parsed.scheme.lower() != "https" or parsed.hostname is None or
                parsed.hostname.lower() != "api.github.com" or parsed.port is not None or
                parsed.username is not None or parsed.password is not None):
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def urlopen(req, timeout=20):
    """Open a GitHub API URL without following redirects off the API origin."""
    return build_opener(GitHubApiRedirectHandler()).open(req, timeout=timeout)


def validate_workflow_run(event, meta, repository, token):
    """Resolve a workflow_run to exactly one live PR using trusted event fields."""
    run = event.get("workflow_run", event)
    head_repo = (run.get("head_repository") or {}).get("full_name")
    head_branch, run_head = run.get("head_branch"), run.get("head_sha")
    if (run.get("event") != "pull_request" or not isinstance(head_repo, str) or
            not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", head_repo) or
            not isinstance(head_branch, str) or not head_branch or
            not isinstance(run_head, str) or not re.fullmatch(r"[0-9a-f]{40,64}", run_head) or
            not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", repository)):
        return None
    owner = head_repo.split("/", 1)[0]
    query = urlencode({"state": "open", "per_page": 100, "head": f"{owner}:{head_branch}"})
    url = f"https://api.github.com/repos/{repository}/pulls?{query}"
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "collie-fork-footprint"}
    with urlopen(Request(url, headers=headers), timeout=20) as response:
        prs = json.load(response)
    matches = [pr for pr in prs if pr.get("head", {}).get("sha") == run_head and
               pr.get("head", {}).get("repo", {}).get("full_name") == head_repo and
               pr.get("base", {}).get("repo", {}).get("full_name") == repository]
    if len(matches) != 1:
        return None
    pr = matches[0]
    number = pr.get("number")
    if (not isinstance(number, int) or number < 1 or meta.get("pr_number") != number or
            meta.get("run_id") != run.get("id") or meta.get("run_head_sha") != run_head or
            meta.get("head_sha") != run_head):
        return None
    for key in ("base_sha", "head_sha", "merge_sha"):
        if not isinstance(meta.get(key), str) or not re.fullmatch(r"[0-9a-f]{40,64}", meta[key]):
            return None
    subprocess.run(["git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
                    "fetch", "--no-tags", "--no-recurse-submodules", "origin", f"refs/pull/{number}/merge"],
                   cwd=REPO, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    merge_sha = subprocess.check_output(["git", "rev-parse", "FETCH_HEAD"], cwd=REPO, text=True).strip()
    merge_parents = subprocess.check_output(["git", "show", "-s", "--format=%P", merge_sha], cwd=REPO, text=True).split()
    if merge_sha != meta["merge_sha"] or merge_parents != [meta["base_sha"], meta["head_sha"]]:
        return None
    pr_url = f"https://api.github.com/repos/{repository}/pulls/{number}"
    with urlopen(Request(pr_url, headers=headers), timeout=20) as response:
        live_pr = json.load(response)
    if (live_pr.get("number") != number or live_pr.get("head", {}).get("repo", {}).get("full_name") != head_repo or
            live_pr.get("base", {}).get("repo", {}).get("full_name") != repository or
            live_pr.get("base", {}).get("sha") != meta["base_sha"] or
            live_pr.get("head", {}).get("sha") != meta["head_sha"]):
        return None
    return {"pr_number": number, "base_sha": meta["base_sha"], "head_sha": meta["head_sha"]}


def live_pr_revisions_match(repository, number, base, head, token):
    """Check that a PR still has the revisions used to generate its report."""
    url = f"https://api.github.com/repos/{repository}/pulls/{number}"
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "collie-fork-footprint"}
    with urlopen(Request(url, headers=headers), timeout=20) as response:
        pr = json.load(response)
    return pr.get("base", {}).get("sha") == base and pr.get("head", {}).get("sha") == head


def sticky_comment(body, expected_base, expected_head):
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    number = os.environ.get("PR_NUMBER") or os.environ.get("GITHUB_PR_NUMBER")
    if not (token and repo and number):
        raise ValueError("posting requires GITHUB_TOKEN, GITHUB_REPOSITORY and PR_NUMBER")
    if not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("GITHUB_REPOSITORY must be an owner/repository name")
    if not re.fullmatch(r"[1-9][0-9]*", number):
        raise ValueError("PR_NUMBER must be a positive decimal number")
    marker = "<!-- collie-fork-footprint -->"
    content = bounded_comment(body)
    api = f"https://api.github.com/repos/{repo}"
    base = f"{api}/issues/{number}/comments"
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "collie-fork-footprint"}
    pr_url = f"{api}/pulls/{number}"
    def revisions_match():
        with urlopen(Request(pr_url, headers=headers), timeout=20) as response:
            current = json.load(response)
        return ((not expected_head or current.get("head", {}).get("sha") == expected_head) and
                (not expected_base or current.get("base", {}).get("sha") == expected_base))

    if not revisions_match():
        print("Comment skipped: the pull request head or base moved after this report was generated.", file=sys.stderr)
        return None
    comments, page = [], base + "?per_page=100"
    while page:
        parsed = urlsplit(page)
        if parsed.scheme != "https" or parsed.netloc != "api.github.com":
            raise ValueError("GitHub comment pagination URL left api.github.com")
        req = Request(page, headers=headers)
        with urlopen(req, timeout=20) as response:
            comments.extend(json.load(response))
            link = response.headers.get("Link", "")
        next_link = re.search(r'<([^>]+)>;\s*rel="next"', link)
        page = next_link.group(1) if next_link else ""
    existing = next((c for c in comments if marker in c.get("body", "") and
                     c.get("user", {}).get("type") == "Bot" and
                     c.get("user", {}).get("login") == "github-actions[bot]"), None)
    url = f"https://api.github.com/repos/{repo}/issues/comments/{existing['id']}" if existing else base
    if not revisions_match():
        print("Comment skipped: the pull request head or base moved before the comment write.", file=sys.stderr)
        return None
    req = Request(url, data=json.dumps({"body": content}).encode(), headers={**headers, "Content-Type": "application/json"}, method="PATCH" if existing else "POST")
    with urlopen(req, timeout=20) as response:
        return json.load(response)


def post_report(repository, number, base, head, report_b64):
    """Validate a bounded trusted report before posting it to its live PR."""
    actual_repository = os.environ.get("EXPECTED_REPOSITORY", os.environ.get("GITHUB_REPOSITORY", ""))
    actual_number = os.environ.get("EXPECTED_PR_NUMBER", os.environ.get("PR_NUMBER", ""))
    actual_base = os.environ.get("EXPECTED_BASE_SHA", base)
    actual_head = os.environ.get("EXPECTED_HEAD_SHA", head)
    if (not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", repository) or
            repository != actual_repository or not re.fullmatch(r"[1-9][0-9]*", number) or number != actual_number):
        raise ValueError("report repository, pull request number, or head SHA is invalid")
    if (not re.fullmatch(r"[0-9a-f]{40,64}", base) or base != actual_base or
            not re.fullmatch(r"[0-9a-f]{40,64}", head) or head != actual_head):
        raise ValueError("report base or head SHA is invalid")
    if not isinstance(report_b64, str) or len(report_b64) > (MAX_COMMENT_BYTES * 4 // 3 + 8):
        raise ValueError("encoded report exceeds the comment size limit")
    try:
        raw = base64.b64decode(report_b64, validate=True)
        report = raw.decode("utf-8")
    except (ValueError, UnicodeDecodeError) as exc:
        raise ValueError("report is not valid base64-encoded UTF-8") from exc
    marker = "<!-- collie-fork-footprint -->"
    if len(raw) > MAX_COMMENT_BYTES or not report.startswith(marker):
        raise ValueError("report is oversized or missing its marker")
    if f"Base: `{base}`" not in report or f"Head: `{head}`" not in report:
        raise ValueError("report base or head SHA does not match the validated pull request")
    os.environ["GITHUB_REPOSITORY"] = repository
    os.environ["PR_NUMBER"] = number
    return sticky_comment(report, base, head)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("dossiers", help="write bounded upstream provenance dossiers")
    p.add_argument("--head", default="HEAD"); p.add_argument("--since"); p.add_argument("--max-kb", type=int, default=150); p.add_argument("--out", type=Path, required=True)
    p = sub.add_parser("entanglement", help="summarize fork integration entanglement")
    p.add_argument("--base", help="base revision (defaults to the latest discovered upstream baseline)"); p.add_argument("--head", default="HEAD"); p.add_argument("--markdown", action="store_true"); p.add_argument("--json", action="store_true")
    p = sub.add_parser("pr-report", help="write a PR report and optionally post a sticky comment")
    p.add_argument("--base", required=True); p.add_argument("--head", required=True); p.add_argument("--post-comment", action="store_true")
    p.add_argument("--markdown", action="store_true"); p.add_argument("--json", action="store_true")
    p = sub.add_parser("post-report", help="validate and post a bounded report from the trusted validation job")
    p.add_argument("--repository", required=True); p.add_argument("--pr", required=True)
    p.add_argument("--base", required=True); p.add_argument("--head", required=True)
    p = sub.add_parser("validate-run", help="validate a workflow_run artifact against its live pull request")
    p.add_argument("--event", type=Path, required=True); p.add_argument("--meta", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "post-report":
            post_report(args.repository, args.pr, args.base, args.head, os.environ.get("REPORT_B64", ""))
        elif args.command == "validate-run":
            with args.event.open(encoding="utf-8") as f:
                event = json.load(f)
            with args.meta.open(encoding="utf-8") as f:
                meta = json.load(f)
            result = validate_workflow_run(event, meta, os.environ.get("GITHUB_REPOSITORY", ""),
                                           os.environ.get("GITHUB_TOKEN", ""))
            if result is None:
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
                    f.write("should_comment=false\n")
                print("Comment skipped: workflow run did not map to one current validated pull request.")
            else:
                report = bounded_comment(pr_markdown(entanglement(result["base_sha"], result["head_sha"],
                                                                  history_ref=result["base_sha"])))
                raw_report = report.encode("utf-8")
                if len(raw_report) > MAX_COMMENT_BYTES or not report.startswith("<!-- collie-fork-footprint -->"):
                    raise ValueError("generated report is oversized or missing its marker")
                repository = os.environ.get("GITHUB_REPOSITORY", "")
                if not live_pr_revisions_match(repository, result["pr_number"], result["base_sha"],
                                               result["head_sha"], os.environ.get("GITHUB_TOKEN", "")):
                    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
                        f.write("should_comment=false\n")
                    print("Comment skipped: the pull request head or base moved while the report was generated.")
                    return 0
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
                    f.write("should_comment=true\n")
                    f.write(f"repository={repository}\n")
                    for key, value in result.items():
                        f.write(f"{key}={value}\n")
                    f.write(f"report_b64={base64.b64encode(raw_report).decode('ascii')}\n")
                summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
                if summary_path:
                    with open(summary_path, "a", encoding="utf-8") as summary:
                        summary.write(report)
        elif args.command == "dossiers":
            if args.max_kb < 1:
                raise ValueError("--max-kb must be positive")
            report = dossiers(args.head, args.out, args.since, args.max_kb)
            print(json.dumps({"head": report["head"], "out": str(args.out), "domains": len(report["domains"]), "leads": report["security_leads"]}, ensure_ascii=True))
        elif args.command == "entanglement":
            report = entanglement(args.base, args.head)
            print(markdown(report) if args.markdown else json.dumps(json_safe(report), indent=2, ensure_ascii=True))
        else:
            report = entanglement(args.base, args.head, history_ref=args.base)
            body = pr_markdown(report)
            summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
            if summary_path:
                with open(summary_path, "a", encoding="utf-8") as f:
                    f.write(body)
            if args.post_comment:
                try:
                    posted = sticky_comment(body, report["base"], report["head"])
                    if posted is None:
                        print("Comment skipped because the pull request revision changed.", file=sys.stderr)
                except HTTPError as exc:
                    if exc.code != 403:
                        raise
                    print(f"Comment unavailable; full report is in GITHUB_STEP_SUMMARY: {exc}", file=sys.stderr)
            print(json.dumps(json_safe(report), indent=2, ensure_ascii=True) if args.json else body, end="" if args.json or body.endswith("\n") else "\n")
        return 0
    except (subprocess.CalledProcessError, OSError, ValueError) as exc:
        print(f"fork_footprint: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
