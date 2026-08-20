# CI Verification Policy

## Purpose

Android emulator jobs are authoritative for device-specific behavior, but they are expensive and can outlive a normal interactive agent session. Verification must therefore be bounded, observable, and evidence-preserving.

## Required policy

1. Every Android build/test job must define an explicit GitHub Actions `timeout-minutes` value. The current limits are **30 minutes** for the JVM/build job and **25 minutes** for the instrumented emulator job.
2. The agent must not poll an active emulator run indefinitely. After one bounded wait interval, it must inspect the job state and either collect reports, diagnose a failure, or stop/cancel the run if it exceeds the declared operational window.
3. JVM test reports must be uploaded with `if: always()` immediately after the JVM test step, so a failed test remains diagnosable without rerunning the entire emulator suite.
4. Instrumented Android reports and coverage must also be uploaded with `if: always()` after the emulator step.
5. A CI result may be reported as passing only when the workflow conclusion is `success`. An in-progress, cancelled, timed-out, or partially successful run must never be described as green.
6. Main-branch merge is prohibited until the isolated branch has a final successful Android CI conclusion and all required security checks, including CodeQL, have final successful conclusions.

## Operational sequence

The expected sequence is: dispatch or push the isolated branch; wait once for a bounded interval; inspect job-level state; retrieve uploaded reports if a prerequisite job fails; cancel an overlong run when appropriate; apply only evidence-based fixes; and dispatch a fresh verification run. The agent should provide a progress checkpoint instead of repeatedly waiting without new information.

## Current issue context

Issue #33 remains open while Dependabot alert #50 is still visible. Issue #34 remains open as a tracking item for the underlying Kotlin/CodeQL compatibility gap, although the repository has a workspace-only CodeQL compatibility fallback and the CodeQL workflow has passed on the isolated branch. Neither issue should be marked resolved solely because a compensating control passes.
