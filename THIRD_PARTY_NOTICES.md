# ForgeOJ third-party notices

> Updated: 2026-09-28. This inventory covers the M-1 scaffold and is not a
> substitute for the full release dependency/license report.

ForgeOJ-authored source that the copyright holder has the right to license is
available under Apache License 2.0; see `LICENSE` and `NOTICE`. The entries
below retain their original licenses and attribution requirements and are not
relicensed by the ForgeOJ root license.

## Generated scaffold and retained files

### Spring Initializr and Spring Boot

- Projects: <https://github.com/spring-io/start.spring.io>,
  <https://github.com/spring-io/initializr>, and
  <https://github.com/spring-projects/spring-boot>
- Versions used: the start.spring.io service observed on 2026-09-27;
  Spring Boot `v4.1.1`
- License: Apache License 2.0
- Use: generated the minimal Java/Maven starting files and supplies framework
  dependencies. No Spring Initializr source tree was copied into this repo.

### Apache Maven Wrapper

- Project: <https://github.com/apache/maven-wrapper>
- Version: `maven-wrapper-3.3.4`, commit
  `524486aff97d0748926a977665d5befb3251ff17`
- License: Apache License 2.0; NOTICE applies
- Retained files: `mvnw`, `mvnw.cmd`, and
  `.mvn/wrapper/maven-wrapper.properties`
- Local modification: `mvnw.cmd` contains a narrow null guard for the Windows
  `.m2` symlink-target lookup, based on upstream issue #395 and draft PR #416;
  the Apache-2.0 header and NOTICE are retained
- Preserved texts:
  `licenses/maven-wrapper-3.3.4-LICENSE.txt` and
  `licenses/maven-wrapper-3.3.4-NOTICE.txt`

### create-vue

- Project: <https://github.com/vuejs/create-vue>
- Version: `v3.22.3`, commit
  `10d767adf0fad58b500e0f2e2149266ef827100e`
- License: the generator is MIT; its template directories and files generated
  from them are CC0 1.0 Universal
- Use: generated the initial `frontend/` files; the generator implementation
  itself was not copied
- Preserved text: `licenses/create-vue-3.22.3-LICENSE.txt`

## Direct dependencies recorded at M-1

- `org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0` — Apache
  License 2.0, <https://github.com/mybatis/spring-boot-starter>
- `com.mysql:mysql-connector-j:9.7.0` — GPL-2.0 with Universal FOSS
  Exception 1.0; Apache-2.0 satisfies the exception's root-license category,
  but every release still requires review of source availability, notices,
  and the actual distribution combination
- `com.h2database:h2:2.4.240` — MPL-2.0 or EPL-1.0; test scope only
- Spring Boot 4.1.1 direct framework/build artifacts — Apache License 2.0
- Frontend runtime dependencies are Vue 3.5.43 and Vue Router 5.3.1 (MIT).
  Exact direct development-tool versions and licenses are listed in
  `docs/DIRECT-DEPENDENCY-LICENSES.md`.
- Maven dependencies are resolved from `pom.xml`; npm dependencies are locked
  by `frontend/package-lock.json`. Their capabilities remain third-party
  capabilities and must not be described as ForgeOJ-authored implementation.
- The CI workflow uses the MIT-licensed `actions/checkout`,
  `actions/setup-java`, and `actions/setup-node` projects, each pinned to the
  full commit recorded in `.github/workflows/ci.yml`.

## Explicitly not imported

No source code, UI, assets, problem content, or business modules were copied
from RuoYi-Vue-Plus, ruoyi-vue-pro, JHipster, CodeJudge, or another OJ.
PageHelper and MyBatis-Plus are not current dependencies.

The exact adopted/rejected references, generated archive hashes, and reuse
boundaries are maintained in `docs/UPSTREAM-AND-LICENSE.md`.
