# ForgeOJ frontend

This is the M-1 Vue 3 and TypeScript scaffold. It does not contain ForgeOJ
business pages yet.

## Install

Use the committed lock file:

```sh
npm ci
```

Node must satisfy `^20.19.0 || >=22.12.0`; the verified local baseline is
Node 24.14.1 with npm 11.11.0.

## Verify

```sh
npm run verify
```

This command runs type-checking, OxcLint and ESLint without fixes, Prettier in
check mode, Vitest once, and the production build.

## Develop

```sh
npm run dev
```

The initial router intentionally has no business routes. Add pages only within
the active Roadmap milestone.
