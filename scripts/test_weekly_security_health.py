#!/usr/bin/env python3
"""Focused unit tests for weekly_security_health policy behavior."""

from __future__ import annotations

import unittest
from datetime import UTC, datetime

from weekly_security_health import GitHubClient, collect_risks, render_markdown


NOW = datetime(2026, 8, 18, tzinfo=UTC)
SUCCESSFUL_RUN = {
    "status": "completed",
    "conclusion": "success",
    "created_at": "2026-08-18T06:00:00Z",
    "name": "Android CI/CD",
    "html_url": "https://example.test/run/1",
}


class GitHubClientPaginationTest(unittest.TestCase):
    def test_get_all_reads_named_list_from_actions_response(self) -> None:
        class FakeClient(GitHubClient):
            def __init__(self) -> None:
                super().__init__("unused", "owner/repo")
                self.calls = 0

            def request(self, method, path, *, query=None, body=None):
                self.calls += 1
                return {"workflow_runs": [{"id": 1}]} if self.calls == 1 else {"workflow_runs": []}

        client = FakeClient()
        runs = client.get_all("/repos/owner/repo/actions/runs", response_list_key="workflow_runs")

        self.assertEqual([{"id": 1}], runs)
        self.assertEqual(1, client.calls)


class WeeklySecurityHealthTest(unittest.TestCase):
    def test_no_risk_for_clean_alerts_successful_ci_and_fresh_pr(self) -> None:
        risks = collect_risks(
            alerts=[],
            workflow_runs=[SUCCESSFUL_RUN],
            dependabot_prs=[
                {
                    "number": 1,
                    "title": "Fresh dependency update",
                    "created_at": "2026-08-16T06:00:00Z",
                    "html_url": "https://example.test/pr/1",
                }
            ],
            now=NOW,
            stale_pr_days=7,
        )

        self.assertEqual([], risks)
        report = render_markdown("owner/repo", NOW, [], [SUCCESSFUL_RUN], [], risks, 7)
        self.assertIn("HEALTHY", report)
        self.assertIn("No policy threshold was breached.", report)

    def test_collects_visibility_unavailable_as_high_risk(self) -> None:
        risks = collect_risks(
            alerts=[],
            workflow_runs=[SUCCESSFUL_RUN],
            dependabot_prs=[],
            now=NOW,
            stale_pr_days=7,
            alert_access_error="HTTP 403: Resource not accessible by integration",
        )

        self.assertEqual(1, len(risks))
        self.assertEqual("dependabot_visibility_unavailable", risks[0].kind)
        self.assertEqual("high", risks[0].severity)
        report = render_markdown(
            "owner/repo",
            NOW,
            [],
            [SUCCESSFUL_RUN],
            [],
            risks,
            7,
            "HTTP 403: Resource not accessible by integration",
        )
        self.assertIn("DEPENDABOT_ALERTS_TOKEN", report)

    def test_collects_alert_failed_ci_and_stale_dependabot_risks(self) -> None:
        alerts = [
            {
                "number": 42,
                "manifest_path": "settings.gradle.kts",
                "dependency": {"package": {"name": "io.netty:netty-handler"}},
                "security_advisory": {
                    "severity": "moderate",
                    "summary": "Netty resource exhaustion",
                    "ghsa_id": "GHSA-example",
                },
                "security_vulnerability": {"first_patched_version": {"identifier": "4.2.17.Final"}},
            }
        ]
        failed_run = {
            **SUCCESSFUL_RUN,
            "conclusion": "failure",
            "html_url": "https://example.test/run/failed",
        }
        stale_pr = {
            "number": 43,
            "title": "Stale dependency update",
            "created_at": "2026-08-01T06:00:00Z",
            "html_url": "https://example.test/pr/43",
        }

        risks = collect_risks(alerts, [failed_run], [stale_pr], now=NOW, stale_pr_days=7)

        self.assertEqual(3, len(risks))
        self.assertEqual({"dependabot_alert", "ci_failure", "stale_dependabot_pr"}, {risk.kind for risk in risks})
        self.assertIn("4.2.17.Final", next(risk.details for risk in risks if risk.kind == "dependabot_alert"))


if __name__ == "__main__":
    unittest.main()
