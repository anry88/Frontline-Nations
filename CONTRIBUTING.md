# Contributing to Frontline Nations

Frontline Nations is currently in repository-bootstrap and specification phase. Contributions should preserve the distinction between planned behavior and working software.

## Before You Start

1. Read [README.md](README.md), [AGENTS.md](AGENTS.md), and [DOCUMENTATION.md](DOCUMENTATION.md).
2. Consult [Documents/Frontline_TZ_v0.1_RU.docx](Documents/Frontline_TZ_v0.1_RU.docx) for detailed product rules.
3. Open an issue or architecture decision for changes that materially alter the product loop, data ownership, battle determinism, economy, or deployment model.

## Change Expectations

- Keep commits focused and use imperative subjects.
- Add tests for new behavior and regression tests for fixes.
- Keep battle simulation deterministic and versioned.
- Make economy and scheduled operations transactional and idempotent.
- Update public and technical documentation when behavior or architecture changes.
- Never commit secrets, local databases, logs, IDE state, or generated build output.

## Verification

Build, test, lint, and local-run commands will be added with the application skeleton. Until then, documentation changes should at minimum pass:

```bash
git diff --check
```

Review staged files before every commit:

```bash
git status --short
git diff --cached
```
