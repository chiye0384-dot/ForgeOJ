# Upstream and scaffold selection

Use this workflow when evaluating a starter repository, importing shared code, or adding a dependency with material license or architecture impact.

## Collect evidence

For every candidate, record:

- canonical repository URL and owner;
- exact release, tag, or commit under evaluation;
- license file, NOTICE requirements, and any separately licensed directories;
- current maintenance signals and supported runtime versions;
- compatibility with the backend baseline recorded in `docs/ForgeOJ-Decision-Log.md`;
- build and test reproducibility on the current machine;
- security-relevant defaults and dependency age;
- generic modules worth retaining;
- unrelated business modules that would need removal;
- conflicts with ForgeOJ requirements or service boundaries.

Use the upstream repository and official framework documentation as primary sources. Popularity is context, not proof of quality or permission.

## Compare candidates

Produce a compact comparison covering:

1. license clarity and obligations;
2. compatibility with the confirmed stack;
3. amount of unrelated business code;
4. effort to understand, test, and maintain retained code;
5. whether reuse saves more work than a minimal official scaffold;
6. effect on interview-explainable ownership.

Include a minimal official-generator baseline, such as Spring Initializr plus the selected frontend generator, so a large third-party scaffold is not chosen by default.

## Approval gate

Recommend one option and state what will be retained, modified, newly implemented, and excluded. Pause for user confirmation before importing an external codebase or accepting a license obligation that is not already approved.

After approval:

- update `docs/UPSTREAM-AND-LICENSE.md` with the immutable upstream reference;
- create or update required license and notice files;
- record ownership boundaries before adding ForgeOJ business code;
- verify a clean build and test baseline;
- do not claim upstream capabilities as ForgeOJ implementation.
