# 账号登录与注册流程修复验收

2026-10-30，状态：**VERIFIED（本地账号流程范围）**。用户要求账号入口默认登录、显式进入注册，完成注册后自动登录。需求与决策见 D-056、[实施计划](ACCOUNT-UX-PLAN.md)。

匿名点击顶部账号、题库的账号与登录，或进入需要认证的普通页面，都会看到真正的登录页，只需要用户名或邮箱、密码。注册、找回和重发使用独立子路由；昵称明确可选，点击顶部账号会回到默认登录。已登录的用户看到账号安全设置。登录后返回原站内页面，返回地址不能指向站外、管理员或账号循环。

注册保持已确认的邮箱验证要求：提交后显示待验证，确认邮件后自动登录。新增 `POST /api/v1/auth/registration/complete` 在同一数据库事务完成令牌消费、账号激活及会话创建，随后签发与普通登录相同的 Cookie 和轮换后的 CSRF。旧只激活接口保持原语义。密码不保存在浏览器持久存储或链接中；邮件令牌在首次打开与同页 fragment 变化时均读取到内存并清除地址及路由历史中的对应值，确认之前不发送激活请求。

本机演示网关单独注入环境标记，账号页显示公开演示账号和邮件模拟限制；普通构建无该标记。真实邮件和公网部署没有在本单元配置。本机体验仍可直接使用 learner / forgeoj-dev-only，无需注册或另外填写邮箱。

| 检查 | 实际结果 |
|---|---|
| 固定 Linux/amd64 账号回归 | 54 tests / 7 suites，失败/错误/跳过均0；包括真实安全链、Cookie/CSRF、激活自动登录、重用拒绝、创建会话失败时回滚激活并重试成功 |
| Windows 前端完整 verify | 类型、oxlint、eslint、Prettier、149 tests / 28 suites、正式构建通过 |
| 实际本地 HTTP | 8项通过：21题保留、待验证拒绝登录、昵称可空、自动登录、CSRF轮换、受保护查询、重用拒绝、退出 |
| 实际浏览器 | 顶部账号默认登录；显式注册再点击账号恢复登录；学习记录跳登录并返回；匿名选题跳统一登录；首次/同页邮件链接均识别；确认后无密码重填直接登录并显示对应测试账号 |
| 构件与入口 | 已更新现有演示API，原MySQL容器/数据挂载保留；本机 Start 再执行通过；36个实际提供静态资源与dist摘要一致，75个前端/网关/检查输入绑定 |

独立机器证据：[verification.json](evidence/account-ux/20261030/verification.json)。页面截图：[默认登录](evidence/account-ux/20261030/login-final-demo.jpg)、[激活后已登录](evidence/account-ux/20261030/activation-signed-in.jpg)。API JAR：`fd5b9939738a167314bea5dda24155c0d7a3849506f322bb7e4d3c3784c14b0b`。构建目录：`target/forgeoj-linux-20261030-095558-219c108b`；Worker为先前验证过的同字节构件复制，未重建，原Worker运行保持。

原始记录保留在 ignored target：`account-ux-backend-verify-first.log`、`account-ux-frontend-final-demo.log`、`account-ux-http-20261030.json`、`account-ux-start-final.log`。此前失败/中间结果也保留：沙箱临时文件EPERM、控制字符正则lint、脱离文档的测试表单未触发默认提交、已有空Worker报告目录导致更新在改动服务前停止、同页fragment激活问题。最终接受结果仅以上述最终记录和独立证据为准。

本次仅使用明确归属的公开测试身份及 `example.test` 注册夹具，未向真实邮箱发信、未清理原数据。两个保护文件仅核对SHA且与原值一致，未读取/修改；未提交推送。此次没有重跑全API或Worker测试；历史336/139结果属于旧构件。本次局部验收不提升完整M5、Release或公网可用状态。
