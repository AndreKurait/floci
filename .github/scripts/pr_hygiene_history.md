# Preserved import history

`pr_hygiene_history.json` records immutable import boundaries for the fork:

- `37f9b6738f80098ec60d474c2dce01b7f41a0309` is the released dev source containing root-volume replacement.
- `622e589dc326c3a95fafeed459f0dda1c2e2a27b` is the upstream main source adopted by the reconciliation.

The original commits keep their authorship, signatures and messages. Rewriting them to pass a newer policy would change the history being preserved.

For same-repository PRs targeting main, the scope resolver subtracts a listed root only when it is an ancestor of the PR head. New ordinary commits still require verified signatures, acceptable attribution and conventional subjects. New text is checked against the preserved content, including intermediate contribution trees.

Other repositories and foreign-fork PRs receive no historical exclusion. PR event fields cannot add an import boundary. The resolved scope records the policy digest and rejects policy or ancestry changes between checks.

Adding a boundary is a separate, reviewable policy change. Never add a new contribution merely to suppress a failed check.
