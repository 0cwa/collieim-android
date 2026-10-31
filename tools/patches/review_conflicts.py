#!/usr/bin/env python3
"""Build a local Git conflict review; use --help for pinned input options.

All supplied objects must already exist in the repository. The report contains
heuristic side choices, file-level exclusions, and a residual independently
applied to a private index to prove the complete target tree. Generated files
in the output directory are overwritten. See the report for scope and caveats.
"""

import argparse, os, sys, json, pathlib, tempfile, subprocess, re, difflib, collections, hashlib, gzip


def run(args, env=None, check=True, input=None):
    p = subprocess.run(
        args, env=env, input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    if check and p.returncode:
        raise RuntimeError((args, p.returncode, p.stderr.decode(errors="replace")))
    return p


def git(*args):
    return run(["git", "-C", R, *args]).stdout


def blob(e):
    return git("cat-file", "blob", e["oid"]) if e and e["mode"] != "160000" else None


def decode(data):
    if data is None or b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def tree_entries(tree):
    result = {}
    for line in git("ls-tree", "-rz", "--full-tree", tree).split(b"\0"):
        if line:
            meta, path = line.split(b"\t", 1)
            mode, typ, oid = meta.decode().split()
            result[os.fsdecode(path)] = {"mode": mode, "oid": oid, "type": typ}
    return result


def text_diff(old, new, old_name, new_name):
    a = decode(blob(old))
    b = decode(blob(new))
    if (old and a is None) or (new and b is None):
        return None
    return delta(a or "", b or "", old_name, new_name)


def editcost(a, b):
    s = difflib.SequenceMatcher(None, a, b, autojunk=False)
    return sum(
        (i2 - i1) + (j2 - j1) for t, i1, i2, j1, j2 in s.get_opcodes() if t != "equal"
    )


def delta(a, b, aa, bb):
    return "".join(
        difflib.unified_diff(
            a.splitlines(True), b.splitlines(True), fromfile=aa, tofile=bb, n=3
        )
    )


pattern = re.compile(
    r"^<<<<<<< MOLLY(?::[^\n]*)?\n(.*?)^=======\n(.*?)^>>>>>>> SIGNAL(?::[^\n]*)?\n",
    re.M | re.S,
)


def validate_preimage(current, before, path):
    if not (
        (not current and before is None)
        or (
            current
            and before is not None
            and current.split()[0:2] == [before["mode"], before["oid"]]
        )
    ):
        raise ValueError("preimage mismatch: " + path)


def apply_choices(original, choices):
    matches = list(pattern.finditer(original))
    if len(matches) != len(choices):
        raise ValueError("choice count does not match marker count")
    parts = []
    pos = 0
    for number, (match, choice) in enumerate(zip(matches, choices), 1):
        if choice.get("ordinal") != number or choice.get("side") not in (
            "old_molly",
            "new_signal",
        ):
            raise ValueError("invalid choice ordinal or side")
        parts.extend(
            [
                original[pos : match.start()],
                match.group(1 if choice["side"] == "old_molly" else 2),
            ]
        )
        pos = match.end()
    parts.append(original[pos:])
    return "".join(parts)


def main():
    global R
    if not __debug__:
        raise RuntimeError("Verification requires Python assertions; do not use -O")
    parser = argparse.ArgumentParser(
        description="Build a reproducible conflict side-choice review using local Git objects.",
        epilog="All Git objects must already exist locally. Choices are heuristic; the residual is independently applied and verified against the complete target tree. Existing generated output files are overwritten.",
    )
    parser.add_argument(
        "--repo", required=True, help="Repository with all pinned objects"
    )
    parser.add_argument(
        "--mechanical-tree", required=True, help="Expected mechanical merge tree ID"
    )
    parser.add_argument(
        "--old-signal", required=True, help="Old Signal merge-base commit"
    )
    parser.add_argument("--molly-source", required=True, help="Old Molly source commit")
    parser.add_argument("--new-signal", required=True, help="New Signal source commit")
    parser.add_argument(
        "--target-tree", required=True, help="Final assembled tree or commit"
    )
    parser.add_argument(
        "--out",
        required=True,
        help="Output directory for the viewer, data, manifest and residual",
    )
    args = parser.parse_args()
    R = str(pathlib.Path(args.repo).resolve())
    O = pathlib.Path(args.out).resolve()
    O.mkdir(parents=True, exist_ok=True)
    pins = {
        "old_signal": args.old_signal,
        "old_molly": args.molly_source,
        "new_signal": args.new_signal,
        "mechanical": args.mechanical_tree,
        "target": args.target_tree,
    }
    with tempfile.TemporaryDirectory(prefix="pr10-choices-") as tmp:
        repo = pathlib.Path(tmp) / "repo"
        run(["git", "init", "--bare", "-q", str(repo)])
        obj = pathlib.Path(tmp) / "objects"
        obj.mkdir()
        idx = str(pathlib.Path(tmp) / "index")
        env = os.environ.copy()
        env.update(
            GIT_CONFIG_NOSYSTEM="1",
            GIT_CONFIG_GLOBAL="/dev/null",
            GIT_NO_LAZY_FETCH="1",
            GIT_OBJECT_DIRECTORY=str(obj),
            GIT_ALTERNATE_OBJECT_DIRECTORIES=git(
                "rev-parse", "--path-format=absolute", "--git-path", "objects"
            )
            .decode()
            .strip(),
            GIT_INDEX_FILE=idx,
            LC_ALL="C",
        )
        for k in list(env):
            if k in (
                "GIT_CONFIG",
                "GIT_CONFIG_PARAMETERS",
                "GIT_CONFIG_COUNT",
                "GIT_ATTR_SOURCE",
            ) or k.startswith(("GIT_CONFIG_KEY_", "GIT_CONFIG_VALUE_")):
                env.pop(k, None)
        cmd = ["git", "--git-dir", str(repo)]
        for key in pins:
            suffix = "^{tree}" if key in ("mechanical", "target") else "^{commit}"
            pins[key] = (
                git("rev-parse", "--verify", pins[key] + suffix).decode().strip()
            )
        for label, key in [("MOLLY", "old_molly"), ("SIGNAL", "new_signal")]:
            run(cmd + ["update-ref", "refs/heads/" + label, pins[key]], env)
        run(cmd + ["symbolic-ref", "HEAD", "refs/heads/MOLLY"], env)
        env["GIT_ATTR_SOURCE"] = "MOLLY"
        opts = [
            "-c",
            "merge.conflictStyle=merge",
            "-c",
            "merge.renames=true",
            "-c",
            "merge.renameLimit=7000",
            "-c",
            "merge.directoryRenames=conflict",
            "-c",
            "merge.renormalize=false",
            "-c",
            "core.autocrlf=false",
            "-c",
            "core.attributesFile=/dev/null",
            "-c",
            "core.hooksPath=/dev/null",
        ]
        merge = run(
            cmd
            + opts
            + [
                "merge-tree",
                "--write-tree",
                "--messages",
                "-Xfind-renames=50%",
                "--merge-base=" + pins["old_signal"],
                "MOLLY",
                "SIGNAL",
            ],
            env,
            False,
        )
        if merge.returncode not in (0, 1) or merge.stderr:
            raise RuntimeError(
                "merge-tree failed: " + merge.stderr.decode(errors="replace")
            )
        lines = merge.stdout.decode().split("\n\n", 1)[0].splitlines()
        if lines[0] != pins["mechanical"]:
            raise RuntimeError(
                "merge tree mismatch: " + lines[0] + " != " + pins["mechanical"]
            )
        stages = collections.defaultdict(dict)
        for line in lines[1:]:
            meta, path = line.split("\t", 1)
            if path.startswith('"'):
                raise RuntimeError("Quoted conflict path unsupported: " + path)
            mode, oid, stage = meta.split()
            stages[path][stage] = {"mode": mode, "oid": oid}
        maps = {key: tree_entries(value) for key, value in pins.items()}
        inventory = []
        for path, stage in sorted(stages.items()):
            entries = {key: value.get(path) for key, value in maps.items()}
            category = (
                "target-absent"
                if entries["target"] is None
                else (
                    "generated-translation"
                    if re.search(r"/values-[^/]*/strings\.xml$", path)
                    else "handmerge-target-present"
                )
            )
            inventory.append(
                {
                    "path": path,
                    "stages": stage,
                    "entries": entries,
                    "category": category,
                }
            )
        run(cmd + ["read-tree", pins["mechanical"]], env)
        manifest = []
        views = []
        total = collections.Counter()

        def set_entry(path, e, data=None):
            if e is None:
                run(cmd + ["update-index", "--force-remove", "--", path], env)
                return None
            oid = (
                e["oid"]
                if data is None
                else run(cmd + ["hash-object", "-w", "--stdin"], env, input=data)
                .stdout.decode()
                .strip()
            )
            run(
                cmd + ["update-index", "--add", "--cacheinfo", e["mode"], oid, path],
                env,
            )
            return {"mode": e["mode"], "oid": oid}

        for item in inventory:
            path = item["path"]
            es = item["entries"]
            mech = es["mechanical"]
            target = es["target"]
            category = item["category"]
            raw = blob(mech)
            text = decode(raw)
            marker_count = len(list(pattern.finditer(text))) if text is not None else 0
            total["all_marker_regions"] += marker_count
            total["all_marker_files"] += bool(marker_count)
            total["all_" + category + "_regions"] += marker_count
            if category != "handmerge-target-present":
                # A whole-file side is an explicit baseline, not a marker-region resolution.
                side = (
                    "target"
                    if category == "generated-translation"
                    else (
                        "mechanical"
                        if not marker_count
                        else (
                            "old_molly"
                            if es["old_molly"]
                            else ("new_signal" if es["new_signal"] else None)
                        )
                    )
                )
                after = set_entry(path, es[side] if side else None)
                manifest.append(
                    {
                        "path": path,
                        "category": category,
                        "before": mech,
                        "method": "whole-file baseline",
                        "side": side,
                        "sources": {
                            "old_molly": es["old_molly"],
                            "new_signal": es["new_signal"],
                            "mechanical": mech,
                            "target": target,
                        },
                        "stages": item["stages"],
                        "after": after,
                        "target": target,
                    }
                )
                total["whole_file_baselines"] += 1
                continue
            regions = []
            if text is not None:
                matches = list(pattern.finditer(text))
                targ = decode(blob(target))
                target_text_available = targ is not None or target is None
                if targ is None:
                    targ = "" if target is None else None
                if targ is None and matches:
                    raise RuntimeError("text conflict with non-text target: " + path)
                if targ is None:
                    targ = ""
                ml = text.splitlines(keepends=True)
                tl = targ.splitlines(keepends=True)
                # Use exact context outside markers as anchors; local windows are independently scored.
                blocks = difflib.SequenceMatcher(
                    None, ml, tl, autojunk=False
                ).get_matching_blocks()
                pieces = []
                pos = 0
                for n, m in enumerate(matches, 1):
                    start = text.count("\n", 0, m.start())
                    end = text.count("\n", 0, m.end())
                    left = [b for b in blocks if b.a + b.size <= start]
                    right = [b for b in blocks if b.a >= end]
                    j1 = left[-1].b + left[-1].size if left else 0
                    j2 = right[0].b if right else len(tl)
                    j2 = max(j1, j2)
                    # Include fixed text between anchors, so scores compare the complete local result.
                    i1 = left[-1].a + left[-1].size if left else 0
                    i2 = right[0].a if right else len(ml)
                    prefix = ml[i1:start]
                    suffix = ml[end:i2]
                    a = m.group(1).splitlines(True)
                    b = m.group(2).splitlines(True)
                    window = tl[j1:j2]
                    ca = editcost(prefix + a + suffix, window)
                    cb = editcost(prefix + b + suffix, window)
                    side = "old_molly" if ca <= cb else "new_signal"
                    chosen = m.group(1) if side == "old_molly" else m.group(2)
                    pieces.extend([text[pos : m.start()], chosen])
                    pos = m.end()
                    # This is a context-based local comparison, not a partition of the global residual.
                    local = "".join(prefix) + chosen + "".join(suffix)
                    target_window = "".join(window)
                    residual = delta(
                        local, target_window, "selected window", "final window"
                    )
                    regions.append(
                        {
                            "ordinal": n,
                            "mechanical_line": start + 1,
                            "side": side,
                            "old_molly": m.group(1),
                            "new_signal": m.group(2),
                            "selected_bytes": len(chosen.encode()),
                            "selected_lines": len(chosen.splitlines()),
                            "window_final_bytes": len(target_window.encode()),
                            "window_final_lines": len(window),
                            "local_residual": residual,
                            "local_residual_bytes": len(residual.encode()),
                            "local_residual_lines": len(residual.splitlines()),
                            "cost_old_molly": ca,
                            "cost_new_signal": cb,
                            "pure_local_pick": local == target_window,
                        }
                    )
                    total["regions"] += 1
                    total["pure_local_picks"] += local == target_window
                pieces.append(text[pos:])
                selected = "".join(pieces)
                assert not pattern.search(selected), path
                after = set_entry(path, mech, selected.encode()) if mech else None
                residual = (
                    delta(selected, targ, "selected/" + path, "final/" + path)
                    if target_text_available
                    else None
                )
            else:
                after = set_entry(path, mech)
                residual = None
            manifest.append(
                {
                    "path": path,
                    "category": category,
                    "before": mech,
                    "method": "region choices" if regions else "file identity",
                    "choices": [
                        {"ordinal": r["ordinal"], "side": r["side"]} for r in regions
                    ],
                    "after": after,
                    "target": target,
                }
            )
            views.append(
                {
                    "path": path,
                    "regions": regions,
                    "residual": residual,
                    "old_molly_delta": text_diff(
                        es["old_signal"],
                        es["old_molly"],
                        "old Signal/" + path,
                        "old Molly/" + path,
                    ),
                    "new_port_delta": text_diff(
                        es["new_signal"], target, "new Signal/" + path, "target/" + path
                    ),
                    "stages": item["stages"],
                    "entries": es,
                }
            )
        baseline = run(cmd + ["write-tree"], env).stdout.decode().strip()

        def diff_stats(before, after, paths):
            if not paths:
                return {
                    "added_lines": 0,
                    "deleted_lines": 0,
                    "text_files": 0,
                    "binary_files": 0,
                }
            output = run(
                cmd
                + ["diff", "--numstat", "-z", "--no-renames", before, after, "--"]
                + [":(literal)" + path for path in paths],
                env,
            ).stdout
            stats = {
                "added_lines": 0,
                "deleted_lines": 0,
                "text_files": 0,
                "binary_files": 0,
            }
            for line in output.split(b"\0"):
                if not line:
                    continue
                added, deleted, path = line.split(b"\t", 2)
                if added == b"-" or deleted == b"-":
                    stats["binary_files"] += 1
                else:
                    stats["text_files"] += 1
                    stats["added_lines"] += int(added)
                    stats["deleted_lines"] += int(deleted)
            return stats

        handmerge_marker_paths = [v["path"] for v in views if v["regions"]]
        raw_stats = diff_stats(
            pins["mechanical"], pins["target"], handmerge_marker_paths
        )
        selected_stats = diff_stats(baseline, pins["target"], handmerge_marker_paths)
        pure_files = sum(
            bool(m["choices"])
            and m["target"] is not None
            and m["after"] is not None
            and m["target"]["oid"] == m["after"]["oid"]
            and m["target"]["mode"] == m["after"]["mode"]
            for m in manifest
            if m["category"] == "handmerge-target-present"
        )
        patch = run(
            cmd
            + [
                "-c",
                "core.quotePath=true",
                "diff",
                "--binary",
                "--full-index",
                "--no-renames",
                baseline,
                pins["target"],
                "--",
            ],
            env,
        ).stdout
        (O / "residual.patch.gz").write_bytes(gzip.compress(patch, mtime=0))
        # Rebuild the manifest into an independent index; blobs reside only in temporary object storage.
        idx2 = str(pathlib.Path(tmp) / "verify-index")
        env2 = env.copy()
        env2["GIT_INDEX_FILE"] = idx2
        run(cmd + ["read-tree", pins["mechanical"]], env2)
        for m in manifest:
            current = (
                run(cmd + ["ls-files", "--stage", "--", m["path"]], env2)
                .stdout.decode()
                .strip()
            )
            before = m["before"]
            validate_preimage(current, before, m["path"])
            after = m["after"]
            if m["method"] == "whole-file baseline":
                source_entry = m["sources"].get(m["side"]) if m["side"] else None
                assert (source_entry is None and after is None) or (
                    source_entry is not None
                    and after is not None
                    and source_entry["mode"] == after["mode"]
                    and source_entry["oid"] == after["oid"]
                ), m["path"]
            if m["method"] == "region choices":
                original = run(
                    cmd + ["cat-file", "blob", before["oid"]], env2
                ).stdout.decode("utf-8")
                rebuilt = apply_choices(original, m["choices"]).encode()
                assert not pattern.search(rebuilt.decode())
                oid = (
                    run(cmd + ["hash-object", "-w", "--stdin"], env2, input=rebuilt)
                    .stdout.decode()
                    .strip()
                )
                assert oid == after["oid"], m["path"]
            if after:
                run(
                    cmd
                    + [
                        "update-index",
                        "--add",
                        "--cacheinfo",
                        after["mode"],
                        after["oid"],
                        m["path"],
                    ],
                    env2,
                )
            else:
                run(cmd + ["update-index", "--force-remove", "--", m["path"]], env2)
        assert run(cmd + ["write-tree"], env2).stdout.decode().strip() == baseline
        # Validate no marker lines in text blobs of all conflict paths in selected baseline.
        markerpaths = []
        for m in manifest:
            e = m["after"]
            data = (
                run(cmd + ["cat-file", "blob", e["oid"]], env2).stdout
                if e and e["mode"] != "160000"
                else b""
            )
            if re.search(rb"^(<<<<<<< |=======\r?$|>>>>>>> )", data, re.M):
                markerpaths.append(m["path"])
        assert not markerpaths, markerpaths
        run(
            cmd + ["apply", "--cached", "--binary", "--whitespace=nowarn", "-"],
            env2,
            input=gzip.decompress((O / "residual.patch.gz").read_bytes()),
        )
        result = run(cmd + ["write-tree"], env2).stdout.decode().strip()
        assert result == pins["target"], (result, pins["target"])
        # Explicit identity comparison of all full-tree paths, modes and object IDs.
        expected = run(
            cmd + ["ls-tree", "-rz", "--full-tree", pins["target"]], env2
        ).stdout
        actual = run(cmd + ["ls-tree", "-rz", "--full-tree", result], env2).stdout
        assert actual == expected
        summary = {
            "pins": pins,
            "baseline_tree": baseline,
            "applied_tree": result,
            "full_tree_path_mode_oid_equal": True,
            "baseline_marker_paths": markerpaths,
            "inventory_counts": {
                "files": len(inventory),
                "marker_files": total["all_marker_files"],
                "marker_regions": total["all_marker_regions"],
                "target_absent": sum(
                    x["category"] == "target-absent" for x in inventory
                ),
                "generated_translation_regions": total[
                    "all_generated-translation_regions"
                ],
            },
            "filtered_counts": {
                "paths": len(views),
                "marker_files": sum(bool(v["regions"]) for v in views),
                "marker_regions": total["regions"],
            },
            "selected_regions": total["regions"],
            "pure_local_picks": total["pure_local_picks"],
            "pure_side_choice_files": pure_files,
            "line_count_scope": "target-present, non-generated conflict paths with marker regions only",
            "raw_conflict_to_target": raw_stats,
            "selected_baseline_to_target": selected_stats,
            "whole_file_baselines": total["whole_file_baselines"],
            "residual_bytes": len(patch),
            "residual_sha256": hashlib.sha256(patch).hexdigest(),
            "method": "Conflict headers identify MOLLY and SIGNAL; base is old Signal from merge-base, not a displayed marker side. For each included marker, score each side by line insertions + deletions against the final between nearest exact matching context anchors, with the other text in that window unchanged; tie selects Molly. Windows may overlap. Choices are a deterministic baseline, not evidence of intent. Local residuals are overlapping contextual illustrations; the complete independently applied residual patch is authoritative. Excluded translations use the final file as an explicit reference; target-absent files use the marker-free mechanical file when possible, otherwise a named whole-file side. These references do not count as side choices. Deltas are path-based and do not follow renames. Modify/delete and rename conflicts remain file-level records.",
        }
        (O / "manifest.json").write_text(
            json.dumps(
                {"summary": summary, "files": manifest},
                ensure_ascii=True,
                separators=(",", ":"),
            )
        )
        (O / "review.json.gz").write_bytes(
            gzip.compress(
                json.dumps(
                    {"summary": summary, "files": views},
                    ensure_ascii=True,
                    separators=(",", ":"),
                ).encode(),
                mtime=0,
            )
        )
        (O / "summary.json").write_text(json.dumps(summary, indent=2))
        viewer = pathlib.Path(__file__).with_name("review_conflicts.html")
        if viewer.resolve() != (O / "view.html").resolve():
            (O / "view.html").write_bytes(viewer.read_bytes())
        print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
