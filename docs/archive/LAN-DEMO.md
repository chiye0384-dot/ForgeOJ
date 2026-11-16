# ForgeOJ 临时局域网演示

2026-10-11 启动的演示网址：<http://172.18.173.127:8088>。

手机或其他电脑须与运行 Docker 的这台电脑连接同一 Wi-Fi。网址中的 IP 随网络变化；关闭电脑、Docker 或本次演示容器后网址失效。这不是公网网址。部分校园网会隔离设备，即使同一 Wi-Fi 也可能无法互访，需要实机确认。

演示账号：`learner` / `forgeoj-dev-only`。另一个独立账号：`other-learner` / `forgeoj-dev-only`。均为独立测试库内的公开测试账号，不要填写真实个人信息。局域网 HTTP 演示在短期登录凭证到期后可能需要重新登录；注册激活和找回密码的真实邮件发送未配置。

可以从题库进入题目、编辑 Java 21 代码、提交判题、运行自测和查看学习记录。2026-10-11 已经通过这个 LAN 网址在浏览器登录并提交两数之和，真实 Worker 返回 FINISHED / AC，提交 ID `905b677d-f655-479f-a2a4-836f2f267c28`。手机连接结果需由手机实测，电脑访问自身 LAN IP 不能证明其他设备可达。

## 构件与运行边界

- 独立 Compose 项目：`forgeoj-e2e-20261011-145312-83d835a3`。
- 状态目录：`target/forgeoj-e2e-20261011-145312-83d835a3`。
- 使用已有固定构件：`target/forgeoj-linux-20261011-092954-14188847`。
- API SHA256：`f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e`。
- Worker SHA256：`ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f`。
- `tools/demo/compose.lan-demo.yml` 叠加在 replay 和 search replay 配置上，前端只发布 Wi-Fi IPv4 的 TCP 8088。
- `tools/demo/serve-lan-demo.mjs` 仅提供已构建的静态页面和 API / WebSocket 代理，不提供源码、开发邮箱或 Docker 接口。
- 临时入口为 HTTP 增加基于 `crypto.getRandomValues` 的 UUID v4 兼容脚本；业务源代码和固定 JAR 未改动。
- Host 校验、原始 Origin、Cookie 和 CSRF 检查保留；客户端 Forwarded / X-Forwarded-* 不被转发。
- 数据库、Redis、搜索和消息队列不发布 LAN 端口。未新增或修改 Windows 防火墙规则。
- 此演示不代表 M5、生产部署或公网发布验收完成。

## 重建演示入口

在仓库根目录 PowerShell 执行下列命令，仅适用于仍存在的上述项目。不要对其他项目执行。

```powershell
$run = Join-Path (Get-Location).Path 'target/forgeoj-e2e-20261011-145312-83d835a3'
$state = Get-Content -LiteralPath (Join-Path $run 'state.json') -Raw | ConvertFrom-Json
$env:FORGEOJ_E2E_SOURCE = (Get-Location).Path
$env:FORGEOJ_E2E_PROJECT = $state.Project
$env:FORGEOJ_E2E_ARTIFACTS = $state.BuildDirectory
$env:FORGEOJ_E2E_REPORTS = $run
$env:FORGEOJ_E2E_REDIS_ENABLED = 'true'
$env:FORGEOJ_DEMO_LAN_IP = '172.18.173.127'
$env:FORGEOJ_DEMO_PORT = '8088'
& 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe' compose --project-name $state.Project -f tools/validation/compose.replay.yml -f tools/validation/compose.search-replay.yml -f tools/demo/compose.lan-demo.yml up --detach --no-deps --force-recreate --wait --wait-timeout 60 frontend
```

如果 Wi-Fi IPv4 变化，先将上面的 `FORGEOJ_DEMO_LAN_IP` 改为当前 WLAN 地址，再执行，使用新网址。

停止且删除本次独立演示（包含演示数据库和提交记录）时使用已有受限脚本；当前正在保留运行供查看，不应立即执行：

```powershell
& tools/validation/Replay-FixedLinux.ps1 -Action Stop -RunDirectory 'target/forgeoj-e2e-20261011-145312-83d835a3' -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
```

不可用关闭 Codex、WSL shutdown、停止整个 Docker 服务或宽泛进程终止替代上述项目停止命令。
