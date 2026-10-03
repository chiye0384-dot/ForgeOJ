SMTP adapter dependency evidence (2026-10-03 Asia/Shanghai)

Actual dependency tree: forgeoj-api/target/smtp-dependency-tree.txt

```
com.forgeoj:forgeoj-api:jar:0.0.1-SNAPSHOT
\- org.springframework.boot:spring-boot-starter-mail:jar:4.1.1:compile
   \- org.springframework.boot:spring-boot-mail:jar:4.1.1:compile
      +- org.springframework:spring-context-support:jar:7.0.9:compile
      +- jakarta.mail:jakarta.mail-api:jar:2.1.5:compile
      |  \- jakarta.activation:jakarta.activation-api:jar:2.1.4:compile
      \- org.eclipse.angus:angus-mail:jar:2.0.5:runtime
         \- org.eclipse.angus:angus-activation:jar:2.0.3:runtime
```

The POMs/JARs are Maven Central artifacts, no copied upstream source. Direct starter and Spring modules are Apache-2.0. Jakarta Mail API and Angus Mail SPDX in POM: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0; aggregate POM also lists EDL-1.0, but do not treat EDL as the whole mail library's license. Activation API/provider license is EDL-1.0 (BSD-style text).

| artifact | POM SHA-256 | JAR SHA-256 | JAR license resources |
|---|---|---|---|
| spring-boot-starter-mail:4.1.1 | 817c0fe44d0c74ac6462848bf0dcb4ea01f666cd6fab7c5425810e288a9ec852 | b9a7ce8ca9a1fea5579fa4c1cf66fb73886c0d1bb7d6fe66015e55dc3b230ec6 | META-INF/LICENSE.txt; META-INF/NOTICE.txt |
| spring-boot-mail:4.1.1 | af25d7c6040e51e925095cf13eda500fae0345c215a4b301edcf3e7fba7da40a | 0e85d4b551cbdd36387be53d31062b057b7d8914eee13255d5242a9078f4d25c | META-INF/LICENSE.txt; META-INF/NOTICE.txt |
| spring-context-support:7.0.9 | 96a783ed9c909171eed1d1c45f8bfc140abcc888b38de4f2a46b2084cd5a3d9f | 3c0c35a0371539f5e7d4e9d0226b9a009a4109cd0e20e805e27132dd6ae0727d | META-INF/license.txt; META-INF/notice.txt |
| jakarta.mail-api:2.1.5 | f03bcb59bb77e1a0099c89063d9d09a6bb4d50d56997a4d5ee1bf58e3864ef43 | aa493753acb7a8c45ba8f4c9cf1230a74e20237056dd5b5c8bc86c583e8cfa0e | META-INF/LICENSE.md; META-INF/NOTICE.md |
| angus-mail:2.0.5 | b2fb76a9ea807b355b8ffa634de84adefac2f279cf60d58cb59c6a6c114c55af | b4d8c30d35f455def6c7a05fe595a1e62ea2b80cac3efec1e9ccf4118b23168a | META-INF/LICENSE.md; META-INF/NOTICE.md |
| angus-activation:2.0.3 | 35c9e16209b2721261354e8aea971626648ad9d4201d221bd96901d900372da0 | a6bd35c538cf90fff941ad6258c40c08fca0b5c9c3f536c657114f27ce0527a7 | META-INF/LICENSE.md; META-INF/NOTICE.md |
| jakarta.activation-api:2.1.4 | 7577970dc09f1131a4a42769e1771ed062f08d22f40da3041ff3e16d5b2fdea8 | c9db52100ce6c8aac95cc39075f95720d2e561b11f8051b81c121ad4effd7004 | META-INF/LICENSE.md; META-INF/NOTICE.md |

Official sources inspected:

- https://docs.spring.io/spring-boot/reference/io/email.html (4.1.1, JavaMailSender/starter, otherwise infinite timeout defaults)
- https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html (required STARTTLS, implicit TLS, identity verification, timeouts)
- https://github.com/spring-projects/spring-boot/tree/v4.1.1
- https://github.com/spring-projects/spring-framework/tree/v7.0.9
- https://raw.githubusercontent.com/eclipse-ee4j/angus-mail/2.0.5/LICENSE.md
- https://raw.githubusercontent.com/jakartaee/mail-api/2.1.5/LICENSE.md
- https://raw.githubusercontent.com/eclipse-ee4j/angus-activation/2.0.3/LICENSE.md
- https://raw.githubusercontent.com/jakartaee/jaf-api/2.1.4/LICENSE.md

Focused verification: .\mvnw.cmd --batch-mode -pl forgeoj-api -Dtest=SmtpAccountMailTests test (JDK21), BUILD SUCCESS at 2026-10-03T09:08:58+08:00; 11 tests / 0 failures / 0 errors / 0 skips. Report forgeoj-api/target/surefire-reports/TEST-com.forgeoj.api.auth.SmtpAccountMailTests.xml. These are real local SMTP/TLS wire checks, never an external provider or mailbox delivery acceptance. Test TLS keypair is generated under JUnit @TempDir during tests with JDK keytool; no certificate or private key is committed, no production global trust store is changed. Parent must check final production JAR for absence of test fixture classes/keys after full fixed-Linux packaging.

Implementation uses required STARTTLS or implicit TLS, TLSv1.2/1.3, default trust/cert+hostname verification, 100..30000ms socket timeout bounds (default5000ms), strict one sender/recipient and safe root application URL, no debug output, no send at startup. Success means relay accepted mail, not inbox delivery. AccountService remains unchanged: post-commit, fixed failure log, eligible user reissue on failure; no durable mail queue/new retry guarantee. Dev/test may use HTTP only for localhost/127.0.0.1/[::1]; production requires HTTPS. API process environment settings belong in .env.example but infrastructure Compose does not export them to the API.
