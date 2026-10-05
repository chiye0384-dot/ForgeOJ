# 本机 QQ 邮箱配置

适用于少量项目验收邮件。服务地址为 `smtp.qq.com`，端口 `465`，TLS 模式 `implicit`；用户名和发件地址均为自己的 QQ 邮箱。QQ 邮箱中开启 POP3/IMAP/SMTP 后，生成专用授权码。不要使用 QQ 登录密码，也不要将授权码发到聊天、提交到 Git 或写入命令参数。

在项目目录的 PowerShell 中运行，替换示例邮箱地址：

```powershell
cd 'D:\Java项目\ForgeOJ'
. .\tools\local\Set-QqMail.ps1 -Email '123456@qq.com'
```

出现提示后输入授权码并回车，输入不会显示。配置脚本不会发邮件。它将授权码用 Windows 当前用户 DPAPI 加密，保存到 Git 忽略的 `.smtp.qq.local`，并设置当前 PowerShell 进程的 API 邮件环境变量。固定 Linux 验收不会复制这个文件。加密文件依然属于本机私密配置，不要分享。

之后重新打开 PowerShell 时，可以加载配置：

```powershell
. .\tools\local\Set-QqMail.ps1 -Load
```

只有从这个 PowerShell 启动的 API 才继承这些环境变量；已经启动的 IntelliJ、API 或 Docker Compose 不会自动切换。默认邮件链接指向 `http://localhost:5173`，仅适合在本机打开；实际部署时用 `-ApplicationUrl 'https://你的站点'`。现有可重复验收 Compose 明确使用 `local` 模拟邮件，不能将其验收当成外部 QQ 投递成功。

SMTP 接受邮件与收件箱实际收到邮件分开验收。真实验收需要使用现有 SMTP 适配器发送，并由邮箱所有者确认收到邮件及链接可用。

配置依据：[腾讯 QQ 邮箱连接说明](https://hiflow.tencent.com/document/applications/qq-mail/)、[腾讯云 SMTP 配置表](https://intl.cloud.tencent.com/zh/document/product/1266/71700)。
