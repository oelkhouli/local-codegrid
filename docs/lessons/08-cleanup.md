# 8. Crashes and physical cleanup

A database lease can expire while a code container still exists. Immediately releasing that reservation would let the scheduler overcommit the node. The database therefore retains uncertain reservations and quarantines the node until cleanup is confirmed.

`NodeLedger` stores local execution intents on a named volume shared by every worker and the janitor. A Java file lock coordinates independent processes. Container creation and cleanup use the same lock. Cleanup writes a persistent tombstone before removing runtime objects. An old worker trying to create another phase afterward sees that tombstone and is rejected.

An attempt uses a deterministic container name derived from job, generation and case index. This makes accidental repeated creation fail instead of spawning an untracked duplicate. Removing a container must be confirmed; a failed runtime operation cannot be treated as proof that the process disappeared.

The node ID is bound to the shared ledger's random identity. Two unrelated computers or two independent volumes cannot silently pretend to be the same node. Every internal call after startup presents the bound identity in addition to the node token.

The janitor is a separate process, so killing a worker does not kill cleanup. A whole-host outage still removes that host's availability; one local computer is not a high-availability deployment. Reservations may remain charged during an outage, which favors resource safety over immediate utilization.

Read `WorkerMain.retire()`, `reconcile()`, and `WorkerService.cleaned()`. Then trace a crash immediately before container creation, immediately afterward, and after result commit but before cleanup acknowledgement.

**Exercise:** kill the worker through the documented fault test and watch the old attempt, retry and cleanup events in the UI. Explain when logical authority ends and when capacity is released.

**Explain it:** “Fencing prevents stale writes. Tombstones and confirmed runtime removal prevent late starts and premature capacity reuse. They solve related but different problems.”
