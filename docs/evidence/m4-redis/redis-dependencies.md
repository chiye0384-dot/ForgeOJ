# M4 Redis 构件事实

2026-10-09；直接使用 Boot 4.1.1 BOM，记录实际解析的 POM/JAR 与其原始告知资源；不复制上游源码，不把客户端、协议或序列化实现算作 ForgeOJ 自有代码。此表不是整个 Release 分发组合的许可验收。

| 构件 | 版本 | POM 许可声明 | JAR SHA-256 |
| --- | --- | --- | --- |
| spring-boot-starter-data-redis | 4.1.1 | Apache License, Version 2.0 | 98b8367cff4cc11005e43452c45dafd0ce9c7f66d20411e6f0850c99bc79575d |
| spring-boot-data-redis | 4.1.1 | Apache License, Version 2.0 | 8df7709a73616c85f12fbee0476a2856a766cda70c6ad87c9a24062b8fe34c91 |
| spring-boot-data-commons | 4.1.1 | Apache License, Version 2.0 | e9911b99825ea5650827ec1e2d2fc1890c7e63b6f048c1f4dba2c8e85ff6d354 |
| spring-data-redis | 4.1.1 | Apache License, Version 2.0 | ad609b5a51bb8591cd923d358125a65d9bea193f79480a37ae60a1d0d5ae55b2 |
| spring-data-keyvalue | 4.1.1 | Apache License, Version 2.0 | e8986c8e2577d271096266335a798c8fb5df91fd2fdf85607252056884fb554d |
| spring-data-commons | 4.1.1 | Apache License, Version 2.0 | cf2a3f9bfabefca1a8c2c0f0a65c393d9ff83b0cb1cf7b705accac18a6ed0ee4 |
| lettuce-core | 7.5.2.RELEASE | MIT | 86e245a6cfc36a40aaa1f54027e639a70cb4008d12717164041aefe9bbd072c1 |
| redis-authx-core | 0.1.1-beta2 | MIT | cc56edb08b3df8562cddac108dca61907d21cc4ad04de05e9f5d1b01058390af |
| reactor-core | 3.8.7 | Apache License, Version 2.0 | 9a5f1bfc5ad0416a410ff63beaa279cc30c2da3ae9b111a678c83c99298a1551 |
| reactive-streams | 1.0.4 | MIT-0 | f75ca597789b3dac58f61857b9ac2e1034a68fa672db35055a8fb4509e325f28 |
| netty-common | 4.2.17.Final | Apache License, Version 2.0 | 502aae2a6680e9bca3558a3fa098f06dc4d87340b8b714e925a6d690871c092a |
| netty-handler | 4.2.17.Final | Apache License, Version 2.0 | 4df11c7520b556c5e2c84b939182699d48fcbb09e97a6bb5e1c0b6835227d126 |
| netty-resolver | 4.2.17.Final | Apache License, Version 2.0 | 7a4b599b9c0c29a505d3b7311f142df883fb27d0f67495a7cbaf533b0e456623 |
| netty-buffer | 4.2.17.Final | Apache License, Version 2.0 | 0d249178ec0204b35a6ad3e5ac8a6c2ecb3eb496c6cdae40b8eca4fa3cb6a0c7 |
| netty-transport-native-unix-common | 4.2.17.Final | Apache License, Version 2.0 | e01aa467b9104de54a57c08c290f70b22e53ec97cf5583c87c112170ef5832cc |
| netty-codec-base | 4.2.17.Final | Apache License, Version 2.0 | 3b92bc3b7d231fa6f4889b2b9281cae6b634428b8121fb1b9b5fccba1543d352 |
| netty-transport | 4.2.17.Final | Apache License, Version 2.0 | d2409ce3a735cbab4188d0ea5d82abed823a6a07a8389f49af4a9e1243a562bc |
| netty-resolver-dns | 4.2.17.Final | Apache License, Version 2.0 | d332481c22136a68c2ee9e45e86212174932587d93a5dd4d201e196828bf9f91 |
| netty-codec-dns | 4.2.17.Final | Apache License, Version 2.0 | 394f4649a824683735f28b299d0a48caff059ae30e654e2abf8b3f392af4d374 |
| netty-codec | 4.2.17.Final | Apache License, Version 2.0 | ac76f972446a2f72818441f746d0e73c58519a4687bf680d2b687d7dc5e28e2e |
| netty-codec-compression | 4.2.17.Final | Apache License, Version 2.0 | 425b287a1f36986a5c8edd76379a49decdf99d284f3454f3fa607a073bfb2c18 |
| netty-codec-protobuf | 4.2.17.Final | Apache License, Version 2.0 | 44b2fcc4bc1d6c01edafe6ae6c8a7de7c3c5bad59697a93b6b879a45a78a5387 |
| netty-codec-marshalling | 4.2.17.Final | Apache License, Version 2.0 | ee5ffed638da6b7e2b4567559bd5f78b18f635cdaa4f3b6d23aa27ccf6cb57c2 |

完整 POM/JAR 与 LICENSE/NOTICE 路径及摘要见 redis-dependencies.json。Spring 项保留 Apache-2.0；本版本 Lettuce 和 Redis Authx 的实际 POM 为 MIT，不能根据旧版本记忆写成 Apache。Reactor/Netty 的上游及内嵌告知仍各自保留；运行包保留原始 JAR。
Redis 服务端使用官方 7.2.16 BSD-3-Clause 镜像；固定镜像摘要及实际 redis-server --version 见设计和最终运行证据；服务端不内嵌 API JAR。
