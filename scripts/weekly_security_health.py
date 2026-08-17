#!/usr/bin/env python3
"""Create a repository security-health summary and upsert one risk-tracking issue.

The script is deliberately deterministic: it uses only GitHub REST data supplied by
GITHUB_TOKEN, writes Markdown/JSON reports, and opens or updates an issue only when
an explicit policy threshold is breached.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

API_ROOT = "https://api.github.com"
USER_AGENT = "vvf-smart-manager-weekly-security-health"
ISSUE_TITLE = "[Security Health] Weekly Dependabot and CI risks detected"
ISSUE_MARKER = "<!-- vvf-weekly-security-health -->"
DEFAULT_STALE_PR_DAYS = 7


@dataclass(frozen=True)
class Risk:
    kind: str
    severity: str
    summary: str
    details: str


class GitHubClient:
    """Minimal standard-library GitHub REST client for scheduled GitHub Actions."""

    def __init__(self, token: str, repository: str) -> None:
        if "/" not in repository:
            raise ValueError("repository must be in owner/name form")
        self.token = token
        self.repository = repository

    def get_all(self, path: str, query: dict[str, str] | None = None) -> list[dict[str, Any]]:
        query = dict(query or {})
        query.setdefault("per_page", "100")
        page = 1
        results: list[dict[str, Any]] = []
        while True:
            query["page"] = str(page)
            payload = self.request("GET", path, query=query)
            if not isinstance(payload, list):
                raise RuntimeError(f"Expected list response for {path}")
            results.extend(payload)
            if len(payload) < int(query["per_page"]):
                return results
            page += 1

    def request(
        self,
        method: str,
        path: str,
        *,
        query: dict[str, str] | None = None,
        body: dict[str, Any] | None = None,
    ) -> Any:
        url = f"{API_ROOT}{path}"
        if query:
            url = f"{url}?{urlencode(query)}"
        encoded = json.dumps(body).encode("utf-8") if body is not None else None
        request = Request(
            url,
            data=encoded,
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "User-Agent": USER_AGENT,
                "X-GitHub-Api-Version": "2022-11-28",
                **({"Content-Type": "application/json"} if encoded else {}),
            },
        )
        try:
            with urlopen(request, timeout=30) as response:  # nosec B310: fixed GitHub API host
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except HTTPError as error:
            details = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"GitHub API {method} {path} failed: HTTP {error.code}: {details}") from error


def parse_timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def normalize_severity(value: str | None) -> str:
    value = (value or "unknown").lower()
    return value if value in {"critical", "high", "moderate", "low"} else "unknown"


def collect_risks(
    alerts: list[dict[str, Any]],
    workflow_runs: list[dict[str, Any]],
    dependabot_prs: list[dict[str, Any]],
    *,
    now: datetime,
    stale_pr_days: int,
) -> list[Risk]:
    risks: list[Risk] = []

    for alert in alerts:
        advisory = alert.get("security_advisory", {})
        dependency = alert.get("dependency", {}).get("package", {}).get("name", "unknown package")
        patched = alert.get("security_vulnerability", {}).get("first_patched_version") or {}
        patch_text = patched.get("identifier", "no patched version published")
        risks.append(
            Risk(
                kind="dependabot_alert",
                severity=normalize_severity(advisory.get("severity")),
                summary=f"{dependency}: {advisory.get('summary', 'Dependabot security alert')}",
                details=(
                    f"Alert #{alert.get('number', 'unknown')} in {alert.get('manifest_path', 'unknown manifest')}; "
                    f"GHSA {advisory.get('ghsa_id', 'unknown')}; first patch: {patch_text}."
                ),
            )
        )

    completed_runs = [run for run in workflow_runs if run.get("status") == "completed"]
    if not completed_runs:
        risks.append(
            Risk(
                kind="ci_missing",
                severity="high",
                summary="No completed default-branch CI workflow run was found.",
                details="The weekly check cannot establish a recent CI baseline.",
            )
        )
    else:
        latest = max(completed_runs, key=lambda run: run.get("created_at", ""))
        conclusion = latest.get("conclusion") or "unknown"
        if conclusion != "success":
            risks.append(
                Risk(
                    kind="ci_failure",
                    severity="high",
                    summary=f"Latest main CI run concluded with '{conclusion}'.",
                    details=f"{latest.get('name', 'workflow')} — {latest.get('html_url', 'no URL')}.",
                )
            )

    for pull_request in dependabot_prs:
        created_at = pull_request.get("created_at")
        if not created_at:
            continue
        age_days = (now - parse_timestamp(created_at)).days
        if age_days >= stale_pr_days:
            risks.append(
                Risk(
                    kind="stale_dependabot_pr",
                    severity="moderate",
                    summary=f"Dependabot PR #{pull_request.get('number')} has been open for {age_days} days.",
                    details=f"{pull_request.get('title', 'Untitled')} — {pull_request.get('html_url', 'no URL')}.",
                )
            )

    return risks


def render_markdown(
    repository: str,
    generated_at: datetime,
    alerts: list[dict[str, Any]],
    workflow_runs: list[dict[str, Any]],
    dependabot_prs: list[dict[str, Any]],
    risks: list[Risk],
    stale_pr_days: int,
) -> str:
    severity_counts = {severity: 0 for severity in ("critical", "high", "moderate", "low", "unknown")}
    for alert in alerts:
        severity_counts[normalize_severity(alert.get("security_advisory", {}).get("severity"))] += 1

    completed_runs = [run for run in workflow_runs if run.get("status") == "completed"]
    latest_run = max(completed_runs, key=lambda run: run.get("created_at", ""), default=None)
    lines = [
        "# Weekly Dependabot and CI Health Summary",
        "",
        f"- **Repository:** `{repository}`",
        f"- **Generated (UTC):** {generated_at.isoformat()}",
        f"- **Risk state:** {'RISK DETECTED' if risks else 'HEALTHY'}",
        "",
        "## Dependabot Alerts",
        "",
        "| Critical | High | Moderate | Low | Unknown | Total |",
        "|---:|---:|---:|---:|---:|---:|",
        "| {critical} | {high} | {moderate} | {low} | {unknown} | {total} |".format(
            **severity_counts, total=len(alerts)
        ),
        "",
        "## Latest Default-Branch CI",
        "",
    ]
    if latest_run:
        lines.extend(
            [
                f"- **Workflow:** {latest_run.get('name', 'unknown')}",
                f"- **Conclusion:** {latest_run.get('conclusion', 'unknown')}",
                f"- **Run:** {latest_run.get('html_url', 'no URL')}",
            ]
        )
    else:
        lines.append("- No completed main-branch workflow run found.")

    lines.extend(
        [
            "",
            "## Open Dependabot Pull Requests",
            "",
            f"- **Count:** {len(dependabot_prs)}",
            f"- **Stale threshold:** {stale_pr_days} days",
        ]
    )
    for pull_request in dependabot_prs:
        lines.append(
            f"- [#{pull_request.get('number')}]({pull_request.get('html_url', '')}) "
            f"{pull_request.get('title', 'Untitled')} — created {pull_request.get('created_at', 'unknown')}."
        )

    lines.extend(["", "## Risks", ""])
    if not risks:
        lines.append("No policy threshold was breached.")
    else:
        lines.extend(["| Severity | Type | Summary | Details |", "|---|---|---|---|"])
        for risk in risks:
            lines.append(f"| {risk.severity} | {risk.kind} | {risk.summary} | {risk.details} |")

    return "\n".join(lines) + "\n"


def upsert_risk_issue(client: GitHubClient, report: str) -> str | None:
    issues = client.get_all(f"/repos/{client.repository}/issues", {"state": "open"})
    matching = [
        issue
        for issue in issues
        if "pull_request" not in issue
        and issue.get("title") == ISSUE_TITLE
        and ISSUE_MARKER in (issue.get("body") or "")
    ]
    body = (
        f"{ISSUE_MARKER}\n"
        "This issue is managed by the weekly security health workflow. Do not close it while the report "
        "still indicates a risk.\n\n" + report
    )
    if matching:
        issue = matching[0]
        client.request("PATCH", f"/repos/{client.repository}/issues/{issue['number']}", body={"body": body})
        return issue.get("html_url")

    created = client.request(
        "POST",
        f"/repos/{client.repository}/issues",
        body={"title": ISSUE_TITLE, "body": body},
    )
    return created.get("html_url")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.getenv("GITHUB_REPOSITORY", ""))
    parser.add_argument("--token", default=os.getenv("GITHUB_TOKEN", ""))
    parser.add_argument("--output-dir", type=Path, default=Path("reports/security-health"))
    parser.add_argument("--stale-pr-days", type=int, default=DEFAULT_STALE_PR_DAYS)
    parser.add_argument("--no-issue", action="store_true", help="Generate reports without creating/updating an issue.")
    args = parser.parse_args()

    if not args.repository or not args.token:
        parser.error("--repository/GITHUB_REPOSITORY and --token/GITHUB_TOKEN are required")
    if args.stale_pr_days < 1:
        parser.error("--stale-pr-days must be at least 1")

    client = GitHubClient(args.token, args.repository)
    alerts = client.get_all(f"/repos/{args.repository}/dependabot/alerts", {"state": "open"})
    workflow_runs = client.get_all(
        f"/repos/{args.repository}/actions/runs",
        {"branch": "main", "event": "push"},
    )
    pull_requests = client.get_all(f"/repos/{args.repository}/pulls", {"state": "open"})
    dependabot_prs = [pull_request for pull_request in pull_requests if pull_request.get("user", {}).get("login") == "dependabot[bot]"]

    now = datetime.now(UTC)
    risks = collect_risks(
        alerts,
        workflow_runs,
        dependabot_prs,
        now=now,
        stale_pr_days=args.stale_pr_days,
    )
    report = render_markdown(
        args.repository,
        now,
        alerts,
        workflow_runs,
        dependabot_prs,
        risks,
        args.stale_pr_days,
    )

    args.output_dir.mkdir(parents=True, exist_ok=True)
    markdown_path = args.output_dir / "weekly-security-health.md"
    json_path = args.output_dir / "weekly-security-health.json"
    markdown_path.write_text(report, encoding="utf-8")
    json_path.write_text(
        json.dumps(
            {
                "repository": args.repository,
                "generated_at": now.isoformat(),
                "alerts": alerts,
                "latest_completed_main_ci": max(
                    (run for run in workflow_runs if run.get("status") == "completed"),
                    key=lambda run: run.get("created_at", ""),
                    default=None,
                ),
                "dependabot_prs": dependabot_prs,
                "risks": [asdict(risk) for risk in risks],
            },
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )

    issue_url = None
    if risks and not args.no_issue:
        issue_url = upsert_risk_issue(client, report)

    summary_path = os.getenv("GITHUB_STEP_SUMMARY")
    if summary_path:
        with Path(summary_path).open("a", encoding="utf-8") as summary_file:
            summary_file.write(report)
            if issue_url:
                summary_file.write(f"\nRisk issue: {issue_url}\n")

    print(f"report={markdown_path}")
    print(f"report_json={json_path}")
    print(f"risk_count={len(risks)}")
    if issue_url:
        print(f"risk_issue={issue_url}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
