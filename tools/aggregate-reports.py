#!/usr/bin/env python3
"""Aggregate lint / test reports across backend / web / android into a single HTML.

Usage:
    tools/aggregate-reports.py lint
    tools/aggregate-reports.py test

Writes ``reports/lint.html`` или ``reports/test.html``. Машиночитаемое сырьё —
под ``reports/raw/``. Exit-код ненулевой, если хоть один компонент сообщил о
проблемах / провалившихся тестах.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / "reports"
RAW = REPORTS / "raw"


# ──────────────────────────────── helpers ──────────────────────────────────────

def banner(label: str) -> None:
    print(f"\n=== {label} ===", flush=True)


def run(
    cmd: list[str],
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    stdout_to: Path | None = None,
) -> int:
    """Run a command, stream stdout/stderr к терминалу (или в файл)."""
    shown = " ".join(cmd)
    print(f"$ {('(cd ' + str(cwd.relative_to(ROOT)) + ') ' if cwd else '')}{shown}", flush=True)
    e = os.environ.copy()
    if env:
        e.update(env)
    if stdout_to:
        stdout_to.parent.mkdir(parents=True, exist_ok=True)
        with stdout_to.open("wb") as f:
            p = subprocess.run(cmd, cwd=str(cwd) if cwd else None, env=e, stdout=f, stderr=subprocess.STDOUT)
        return p.returncode
    p = subprocess.run(cmd, cwd=str(cwd) if cwd else None, env=e)
    return p.returncode


def android_env() -> dict[str, str]:
    """Load android/local.env и собрать SOCKS5 GRADLE_OPTS."""
    env: dict[str, str] = {}
    local = ROOT / "android" / "local.env"
    if not local.exists():
        return env
    for line in local.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()
    socks_host = env.get("SOCKS_PROXY_HOST", "")
    socks_port = env.get("SOCKS_PROXY_PORT", "1080")
    opts = []
    if socks_host:
        opts += [f"-DsocksProxyHost={socks_host}", f"-DsocksProxyPort={socks_port}", "-DsocksProxyVersion=5"]
    opts += ["-Dorg.gradle.internal.http.socketTimeout=300000"]
    env["GRADLE_OPTS"] = " ".join(opts)
    return env


def rel(path: str) -> str:
    if not path:
        return ""
    try:
        return str(Path(path).resolve().relative_to(ROOT))
    except Exception:
        return path


def esc(x) -> str:
    return html.escape(str(x))


# ──────────────────────────────── LINT runners ────────────────────────────────

def lint_backend() -> dict:
    banner("lint: backend")
    out_json = RAW / "backend-lint.json"
    out_json.unlink(missing_ok=True)
    # gofmt drift
    gofmt_path = RAW / "backend-gofmt.txt"
    run(["gofmt", "-l", "."], cwd=ROOT / "backend", stdout_to=gofmt_path)
    gofmt_dirty = [l.strip() for l in gofmt_path.read_text(errors="replace").splitlines() if l.strip()]
    # go vet (just record exit code; details go to terminal)
    vet_rc = run(["go", "vet", "./..."], cwd=ROOT / "backend")
    # golangci-lint JSON
    rc_lint = run(
        ["golangci-lint", "run", f"--output.json.path={out_json}", "./..."],
        cwd=ROOT / "backend",
    )
    issues = []
    if out_json.exists() and out_json.stat().st_size > 0:
        try:
            data = json.loads(out_json.read_text())
            for it in data.get("Issues") or []:
                issues.append({
                    "rule": it.get("FromLinter") or "?",
                    "file": it.get("Pos", {}).get("Filename") or "?",
                    "line": it.get("Pos", {}).get("Line") or 0,
                    "msg": (it.get("Text") or "").strip(),
                })
        except json.JSONDecodeError:
            pass
    return {
        "name": "Backend (Go)",
        "tools": ["gofmt", "go vet", "golangci-lint"],
        "issues": issues,
        "extra_findings": [
            ("gofmt", "файл не отформатирован", gofmt_dirty),
        ],
        "rc": max(rc_lint, vet_rc, 1 if gofmt_dirty else 0),
    }


def lint_web() -> dict:
    banner("lint: web")
    eslint_path = RAW / "web-eslint.json"
    eslint_path.unlink(missing_ok=True)
    # ESLint без --max-warnings=0 — нам нужны все сообщения, не early-exit.
    rc_eslint = run(
        ["corepack", "yarn", "eslint", "-f", "json", "-o", str(eslint_path), "."],
        cwd=ROOT / "frontend",
    )
    issues = []
    if eslint_path.exists() and eslint_path.stat().st_size > 0:
        try:
            data = json.loads(eslint_path.read_text())
            for f in data:
                for m in f.get("messages") or []:
                    issues.append({
                        "rule": m.get("ruleId") or ("error" if m.get("fatal") else "?"),
                        "severity": "error" if m.get("severity", 0) >= 2 else "warn",
                        "file": f.get("filePath") or "?",
                        "line": m.get("line") or 0,
                        "msg": (m.get("message") or "").strip(),
                    })
        except json.JSONDecodeError:
            pass
    # Prettier — текст в stdout, на drift exit 1.
    pret_path = RAW / "web-prettier.txt"
    rc_pret = run(["corepack", "yarn", "prettier", "--check", "."], cwd=ROOT / "frontend", stdout_to=pret_path)
    pret_files: list[str] = []
    if pret_path.exists():
        for line in pret_path.read_text(errors="replace").splitlines():
            # формат: "[warn] path/to/file.vue" (с табом или пробелами)
            m = re.match(r"\[warn\]\s+(.+\.\w+)\s*$", line.strip())
            if m:
                pret_files.append(m.group(1))
    return {
        "name": "Web (Vue 3)",
        "tools": ["eslint", "prettier"],
        "issues": issues,
        "extra_findings": [
            ("prettier", "файл не отформатирован", pret_files),
        ],
        "rc": max(rc_eslint, rc_pret),
    }


def lint_android() -> dict:
    banner("lint: android")
    env = android_env()
    rc = run(
        ["./gradlew", "--no-daemon", ":app:ktlintCheck", ":app:detekt"],
        cwd=ROOT / "android",
        env=env,
    )
    issues: list[dict] = []
    # ktlint → checkstyle XML
    kt_xml = ROOT / "android" / "app" / "build" / "reports" / "ktlint" / "ktlint.xml"
    if kt_xml.exists():
        try:
            for f in ET.parse(str(kt_xml)).findall("file"):
                fname = f.get("name") or "?"
                for e in f.findall("error"):
                    issues.append({
                        "rule": f'ktlint:{(e.get("source") or "?").removeprefix("standard:")}',
                        "file": fname,
                        "line": int(e.get("line") or 0),
                        "msg": (e.get("message") or "").strip(),
                    })
        except ET.ParseError:
            pass
    # detekt → checkstyle XML
    det_xml = ROOT / "android" / "app" / "build" / "reports" / "detekt" / "detekt.xml"
    if det_xml.exists():
        try:
            for f in ET.parse(str(det_xml)).findall("file"):
                fname = f.get("name") or "?"
                for e in f.findall("error"):
                    issues.append({
                        "rule": f'detekt:{(e.get("source") or "?").removeprefix("detekt.")}',
                        "file": fname,
                        "line": int(e.get("line") or 0),
                        "msg": (e.get("message") or "").strip(),
                    })
        except ET.ParseError:
            pass
    return {
        "name": "Android (Kotlin)",
        "tools": ["ktlint", "detekt"],
        "issues": issues,
        "extra_findings": [],
        "rc": rc,
    }


# ──────────────────────────────── TEST runners ────────────────────────────────

def test_backend() -> dict:
    banner("test: backend")
    out_json = RAW / "backend-test.json"
    # Без -short: запускаем и интеграционные тесты (нужны для адекватного
    # coverage по handlers/repository через testcontainers; пакет mongotest
    # сам делает t.Skipf если Docker недоступен — на машине без Docker это
    # просто проявится как skip, а не как fail).
    rc = run(
        [
            "go", "test", "-race", "-json",
            "-coverprofile=coverage.out", "-covermode=atomic", "-coverpkg=./...",
            "./...",
        ],
        cwd=ROOT / "backend",
        stdout_to=out_json,
    )
    cases: list[dict] = []
    if out_json.exists():
        with out_json.open(errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    ev = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if ev.get("Test") and ev.get("Action") in ("pass", "fail", "skip"):
                    cases.append({
                        "pkg": ev.get("Package") or "?",
                        "name": ev.get("Test") or "?",
                        "status": ev["Action"],
                        "duration": float(ev.get("Elapsed") or 0.0),
                    })
    # coverage
    cov_pct = None
    cov_per_pkg: list[tuple[str, float]] = []
    cov_out = ROOT / "backend" / "coverage.out"
    if cov_out.exists():
        cov_txt = RAW / "backend-coverage.txt"
        run(["go", "tool", "cover", "-func=coverage.out"], cwd=ROOT / "backend", stdout_to=cov_txt)
        if cov_txt.exists():
            for line in cov_txt.read_text(errors="replace").splitlines():
                if line.startswith("total:"):
                    m = re.search(r"(\d+(?:\.\d+)?)%", line)
                    if m:
                        cov_pct = float(m.group(1))
                else:
                    m = re.match(r"^([^\s]+\.go):\d+:\s+\S+\s+(\d+(?:\.\d+)?)%", line)
                    if m:
                        # агрегируем по пакету (путь до файла без файла)
                        pkg = str(Path(m.group(1)).parent)
                        cov_per_pkg.append((pkg, float(m.group(2))))
    # сжимаем per-pkg в усреднённый % (упрощённо — среднее по функциям)
    pkg_avg: dict[str, list[float]] = {}
    for pkg, p in cov_per_pkg:
        pkg_avg.setdefault(pkg, []).append(p)
    pkg_summary = sorted(
        ((pkg, sum(v) / len(v)) for pkg, v in pkg_avg.items()),
        key=lambda x: x[0],
    )
    return {
        "name": "Backend (Go)",
        "cases": cases,
        "coverage": cov_pct,
        "coverage_by_pkg": pkg_summary,
        "rc": rc,
    }


def test_web() -> dict:
    banner("test: web")
    junit_path = RAW / "web-test.xml"
    junit_path.unlink(missing_ok=True)
    rc = run(
        [
            "corepack", "yarn", "vitest", "run",
            "--reporter=default",
            "--reporter=junit",
            f"--outputFile.junit={junit_path}",
            "--coverage",
            "--coverage.reporter=text",
            "--coverage.reporter=json-summary",
            "--coverage.reporter=html",
        ],
        cwd=ROOT / "frontend",
    )
    cases: list[dict] = []
    if junit_path.exists():
        try:
            tree = ET.parse(str(junit_path))
            for ts in tree.getroot().iter("testsuite"):
                suite = ts.get("name") or "?"
                for tc in ts.findall("testcase"):
                    status = "pass"
                    if tc.find("failure") is not None or tc.find("error") is not None:
                        status = "fail"
                    elif tc.find("skipped") is not None:
                        status = "skip"
                    cases.append({
                        "pkg": suite,
                        "name": tc.get("name") or "?",
                        "status": status,
                        "duration": float(tc.get("time") or 0.0),
                    })
        except ET.ParseError:
            pass
    cov_pct = None
    cov_by: list[tuple[str, float]] = []
    cov_json = ROOT / "frontend" / "coverage" / "coverage-summary.json"
    if cov_json.exists():
        try:
            data = json.loads(cov_json.read_text())
            cov_pct = data.get("total", {}).get("lines", {}).get("pct")
            for key, val in data.items():
                if key == "total":
                    continue
                pct = val.get("lines", {}).get("pct")
                if pct is not None:
                    cov_by.append((rel(key), float(pct)))
            cov_by.sort()
        except json.JSONDecodeError:
            pass
    return {
        "name": "Web (Vue 3)",
        "cases": cases,
        "coverage": cov_pct,
        "coverage_by_pkg": cov_by[:40],
        "rc": rc,
    }


def test_android() -> dict:
    banner("test: android")
    env = android_env()
    rc = run(
        ["./gradlew", "--no-daemon", ":app:jacocoTestReport"],
        cwd=ROOT / "android",
        env=env,
    )
    cases: list[dict] = []
    results_dir = ROOT / "android" / "app" / "build" / "test-results" / "testDebugUnitTest"
    if results_dir.exists():
        for xml in sorted(results_dir.glob("TEST-*.xml")):
            try:
                tree = ET.parse(str(xml))
            except ET.ParseError:
                continue
            suite = tree.getroot().get("name") or xml.stem
            for tc in tree.getroot().iter("testcase"):
                status = "pass"
                if tc.find("failure") is not None or tc.find("error") is not None:
                    status = "fail"
                elif tc.find("skipped") is not None:
                    status = "skip"
                cases.append({
                    "pkg": suite,
                    "name": tc.get("name") or "?",
                    "status": status,
                    "duration": float(tc.get("time") or 0.0),
                })
    cov_pct = None
    cov_by: list[tuple[str, float]] = []
    jacoco_xml = (
        ROOT / "android" / "app" / "build" / "reports" / "jacoco" / "jacocoTestReport" / "jacocoTestReport.xml"
    )
    if jacoco_xml.exists():
        try:
            root = ET.parse(str(jacoco_xml)).getroot()
            line = next((c for c in root.findall("counter") if c.get("type") == "LINE"), None)
            if line is not None:
                missed = int(line.get("missed") or 0)
                covered = int(line.get("covered") or 0)
                total = missed + covered
                if total > 0:
                    cov_pct = round(100 * covered / total, 1)
            for pkg in root.findall("package"):
                pkg_line = next((c for c in pkg.findall("counter") if c.get("type") == "LINE"), None)
                if pkg_line is None:
                    continue
                m = int(pkg_line.get("missed") or 0)
                c = int(pkg_line.get("covered") or 0)
                if m + c > 0:
                    cov_by.append((pkg.get("name") or "?", round(100 * c / (m + c), 1)))
            cov_by.sort()
        except ET.ParseError:
            pass
    return {
        "name": "Android (Kotlin)",
        "cases": cases,
        "coverage": cov_pct,
        "coverage_by_pkg": cov_by,
        "rc": rc,
    }


# ──────────────────────────────── HTML rendering ──────────────────────────────

CSS = """
body { font: 14px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #fafafa; color: #222; margin: 0; padding: 24px; max-width: 1100px; }
h1 { margin: 0 0 6px; font-size: 22px; }
h2 { margin: 0; font-size: 18px; }
h3 { margin: 18px 0 6px; font-size: 14px; }
.meta { color: #888; font-size: 12px; margin-bottom: 24px; }
.section { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 18px 22px; margin-bottom: 16px; }
.head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.badge { display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
.b-pass { background: #d4f8dc; color: #06873b; }
.b-fail { background: #fde2e2; color: #c12626; }
.b-warn { background: #fdf3d4; color: #8a5d00; }
.b-skip { background: #eee; color: #555; }
.tag { display: inline-block; background: #eef; color: #345; padding: 1px 8px; border-radius: 4px; font-size: 11px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 6px; }
th { text-align: left; padding: 6px 8px; background: #f5f5f5; font-weight: 600; border-bottom: 1px solid #e5e5e5; }
td { padding: 6px 8px; border-top: 1px solid #f0f0f0; vertical-align: top; }
td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
td.r { color: #c12626; }
td.g { color: #06873b; }
td.code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; word-break: break-all; }
.muted { color: #888; }
.stats { display: flex; gap: 28px; margin: 10px 0 4px; flex-wrap: wrap; }
.stat .l { font-size: 11px; text-transform: uppercase; color: #888; letter-spacing: 0.5px; }
.stat .v { font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; }
details { margin-top: 8px; }
details summary { cursor: pointer; padding: 4px 0; color: #345; }
.bar { background: #eee; border-radius: 3px; height: 6px; width: 80px; display: inline-block; overflow: hidden; vertical-align: middle; }
.bar > i { display: block; height: 6px; background: #06873b; }
.bar.low > i { background: #c12626; }
.bar.mid > i { background: #c98800; }
"""


def now() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def coverage_bar(pct: float) -> str:
    cls = "low" if pct < 30 else ("mid" if pct < 60 else "")
    return f'<span class="bar {cls}"><i style="width:{min(100, pct):.1f}%"></i></span>'


def render_lint(results: list[dict]) -> str:
    parts: list[str] = []
    parts.append('<!DOCTYPE html><html lang="ru"><head><meta charset="utf-8">')
    parts.append("<title>Lint Report — budget-go</title>")
    parts.append(f"<style>{CSS}</style></head><body>")
    parts.append("<h1>Lint Report — budget-go</h1>")
    parts.append(
        f'<div class="meta">сгенерировано {now()} · <code>tools/aggregate-reports.py lint</code> · '
        f'отчёт по компонентам api / web / android</div>'
    )

    # overall summary
    parts.append('<div class="section"><h2>Итог</h2>')
    parts.append('<table><thead><tr><th>компонент</th><th>инструменты</th><th class="num">проблем</th><th>статус</th></tr></thead><tbody>')
    for r in results:
        total = len(r["issues"]) + sum(len(f[2]) for f in r["extra_findings"])
        if total == 0:
            badge = '<span class="badge b-pass">clean</span>'
        else:
            badge = f'<span class="badge b-fail">{total}</span>'
        parts.append(
            f'<tr><td>{esc(r["name"])}</td>'
            f'<td>{" ".join(f"<span class=tag>{esc(t)}</span>" for t in r["tools"])}</td>'
            f'<td class="num">{total}</td>'
            f"<td>{badge}</td></tr>"
        )
    parts.append("</tbody></table></div>")

    for r in results:
        total = len(r["issues"]) + sum(len(f[2]) for f in r["extra_findings"])
        badge = '<span class="badge b-pass">clean</span>' if total == 0 else f'<span class="badge b-fail">{total} issues</span>'
        parts.append(f'<div class="section"><div class="head"><h2>{esc(r["name"])}</h2>{badge}')
        parts.append("</div>")

        if r["issues"]:
            counter = Counter(i["rule"] for i in r["issues"])
            parts.append("<h3>По правилам</h3>")
            parts.append('<table><thead><tr><th>правило</th><th class="num">количество</th></tr></thead><tbody>')
            for rule, cnt in counter.most_common():
                parts.append(f'<tr><td class="code">{esc(rule)}</td><td class="num">{cnt}</td></tr>')
            parts.append("</tbody></table>")

            parts.append(f'<details><summary>Список проблем ({len(r["issues"])})</summary>')
            parts.append('<table><thead><tr><th>файл</th><th class="num">строка</th><th>правило</th><th>сообщение</th></tr></thead><tbody>')
            for it in r["issues"][:300]:
                parts.append(
                    f'<tr><td class="code">{esc(rel(it["file"]))}</td>'
                    f'<td class="num">{esc(it["line"])}</td>'
                    f'<td class="code">{esc(it["rule"])}</td>'
                    f'<td>{esc(it["msg"])}</td></tr>'
                )
            if len(r["issues"]) > 300:
                parts.append(f'<tr><td colspan="4" class="muted">… усечено до 300 (из {len(r["issues"])})</td></tr>')
            parts.append("</tbody></table></details>")

        for tool, label, files in r["extra_findings"]:
            if files:
                parts.append(f"<h3>{esc(tool)}: {esc(label)} ({len(files)})</h3><ul>")
                for f in files:
                    parts.append(f'<li class="code">{esc(rel(f))}</li>')
                parts.append("</ul>")

        if total == 0:
            parts.append('<p class="muted">Никаких проблем.</p>')
        parts.append("</div>")

    parts.append("</body></html>")
    return "\n".join(parts)


def render_test(results: list[dict]) -> str:
    parts: list[str] = []
    parts.append('<!DOCTYPE html><html lang="ru"><head><meta charset="utf-8">')
    parts.append("<title>Test Report — budget-go</title>")
    parts.append(f"<style>{CSS}</style></head><body>")
    parts.append("<h1>Test Report — budget-go</h1>")
    parts.append(
        f'<div class="meta">сгенерировано {now()} · <code>tools/aggregate-reports.py test</code> · '
        f"статус / длительность / coverage по компонентам api / web / android</div>"
    )

    # overall
    parts.append('<div class="section"><h2>Итог</h2>')
    parts.append(
        '<table><thead><tr><th>компонент</th>'
        '<th class="num">passed</th><th class="num">failed</th><th class="num">skipped</th>'
        '<th class="num">время, с</th><th>coverage</th><th>статус</th></tr></thead><tbody>'
    )
    for r in results:
        passed = sum(1 for c in r["cases"] if c["status"] == "pass")
        failed = sum(1 for c in r["cases"] if c["status"] == "fail")
        skipped = sum(1 for c in r["cases"] if c["status"] == "skip")
        dur = sum(c["duration"] for c in r["cases"])
        cov = r.get("coverage")
        cov_cell = (
            f'<td>{coverage_bar(cov)} {cov:.1f}%</td>' if cov is not None
            else '<td class="muted">—</td>'
        )
        if failed > 0:
            badge = f'<span class="badge b-fail">{failed} failed</span>'
        elif not r["cases"]:
            badge = '<span class="badge b-warn">no data</span>'
        else:
            badge = '<span class="badge b-pass">passed</span>'
        parts.append(
            f'<tr><td>{esc(r["name"])}</td>'
            f'<td class="num g">{passed}</td>'
            f'<td class="num {"r" if failed else ""}">{failed}</td>'
            f'<td class="num">{skipped}</td>'
            f'<td class="num">{dur:.2f}</td>'
            f"{cov_cell}"
            f"<td>{badge}</td></tr>"
        )
    parts.append("</tbody></table></div>")

    for r in results:
        cases = r["cases"]
        passed = sum(1 for c in cases if c["status"] == "pass")
        failed = sum(1 for c in cases if c["status"] == "fail")
        skipped = sum(1 for c in cases if c["status"] == "skip")
        dur = sum(c["duration"] for c in cases)
        cov = r.get("coverage")

        if failed > 0:
            badge = f'<span class="badge b-fail">{failed} failed</span>'
        elif not cases:
            badge = '<span class="badge b-warn">no data</span>'
        else:
            badge = f'<span class="badge b-pass">{passed} passed</span>'

        parts.append(f'<div class="section"><div class="head"><h2>{esc(r["name"])}</h2>{badge}</div>')
        parts.append('<div class="stats">')
        parts.append(f'<div class="stat"><div class="l">passed</div><div class="v">{passed}</div></div>')
        parts.append(f'<div class="stat"><div class="l">failed</div><div class="v">{failed}</div></div>')
        parts.append(f'<div class="stat"><div class="l">skipped</div><div class="v">{skipped}</div></div>')
        parts.append(f'<div class="stat"><div class="l">время, с</div><div class="v">{dur:.2f}</div></div>')
        if cov is not None:
            parts.append(f'<div class="stat"><div class="l">coverage (line)</div><div class="v">{cov:.1f}%</div></div>')
        parts.append("</div>")

        failed_cases = [c for c in cases if c["status"] == "fail"]
        if failed_cases:
            parts.append("<h3>Упавшие тесты</h3>")
            parts.append('<table><thead><tr><th>пакет / suite</th><th>тест</th><th class="num">сек</th></tr></thead><tbody>')
            for c in failed_cases:
                parts.append(
                    f'<tr><td class="code">{esc(c["pkg"])}</td>'
                    f'<td>{esc(c["name"])}</td>'
                    f'<td class="num">{c["duration"]:.3f}</td></tr>'
                )
            parts.append("</tbody></table>")

        if cases:
            slowest = sorted(cases, key=lambda c: -c["duration"])[:10]
            parts.append('<details><summary>10 самых медленных</summary>')
            parts.append('<table><thead><tr><th>пакет / suite</th><th>тест</th><th class="num">сек</th></tr></thead><tbody>')
            for c in slowest:
                parts.append(
                    f'<tr><td class="code">{esc(c["pkg"])}</td>'
                    f'<td>{esc(c["name"])}</td>'
                    f'<td class="num">{c["duration"]:.3f}</td></tr>'
                )
            parts.append("</tbody></table></details>")

        cov_by = r.get("coverage_by_pkg") or []
        if cov_by:
            parts.append('<details><summary>Coverage по пакетам / файлам</summary>')
            parts.append('<table><thead><tr><th>пакет / файл</th><th>покрытие</th><th class="num">%</th></tr></thead><tbody>')
            for name, pct in cov_by:
                parts.append(
                    f'<tr><td class="code">{esc(name)}</td>'
                    f"<td>{coverage_bar(pct)}</td>"
                    f'<td class="num">{pct:.1f}</td></tr>'
                )
            parts.append("</tbody></table></details>")

        if not cases:
            parts.append('<p class="muted">Тестовые результаты не найдены.</p>')

        parts.append("</div>")

    parts.append("</body></html>")
    return "\n".join(parts)


# ──────────────────────────────── main ────────────────────────────────────────

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["lint", "test"])
    args = parser.parse_args()

    RAW.mkdir(parents=True, exist_ok=True)

    if args.mode == "lint":
        results = [lint_backend(), lint_web(), lint_android()]
        out = REPORTS / "lint.html"
        out.write_text(render_lint(results))
        print(f"\nLint report: {out}")
        total = sum(len(r["issues"]) + sum(len(f[2]) for f in r["extra_findings"]) for r in results)
        return 0 if total == 0 else 1

    results = [test_backend(), test_web(), test_android()]
    out = REPORTS / "test.html"
    out.write_text(render_test(results))
    print(f"\nTest report: {out}")
    any_fail = any(any(c["status"] == "fail" for c in r["cases"]) or r["rc"] != 0 for r in results)
    return 0 if not any_fail else 1


if __name__ == "__main__":
    sys.exit(main())
