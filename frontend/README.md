# ForgeOJ frontend

This Vue 3 and TypeScript application contains the M0 minimum judge workspace:
session login, the built-in problem, Java 21 submission, and result polling.

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

The Vite development server proxies `/api` to `http://localhost:8080`. Start the
ForgeOJ API separately with the `dev` profile when exercising the real flow.
The current automated frontend flow uses mocked API responses; it does not by
itself prove the browser/API/Worker end-to-end path.
