# 本地开发与格式约定

Java 21、Maven Wrapper和Docker是后端检查的前提；前端使用package-lock锁定的Node/npm依赖。业务Java与测试统一采用Spotless 3.0.0、google-java-format 1.28.0，UTF-8/LF，不依赖个人IDE配置。

Windows的验证、打包和部署脚本使用PowerShell 7（`pwsh`）；在已打开的PowerShell 7中可直接调用`.ps1`脚本。

```text
# 修改后自动排版（Windows用 .\mvnw.cmd）
./mvnw --batch-mode spotless:apply

# 只检查，不改文件
./mvnw --batch-mode spotless:check

# 编译及集成回归；validate阶段也会检查格式
./mvnw --batch-mode verify
```

CI有独立Java格式门禁，后端verify、前端npm run verify分别执行。集成测试启动独立MySQL、RabbitMQ、Elasticsearch与Docker沙箱；不要同时运行另一个受管沙箱验证。需要固定Linux运行时，使用`tools/validation/Verify-FixedLinux.ps1`。

长MyBatis SQL优先使用Java文本块，将SELECT/FROM/WHERE/SET及条件分行；保留具名参数。仅排版不迁移schema、不更改条件或改为字符串拼接用户输入。常用类型使用import；同名冲突时才保留必要的完整类名。SQL排版需要真实数据库回归，格式通过不能代替业务验证。

开始前看Git状态并保留已有修改。不要在包含Docker数据盘或备份的宿主目录执行清理；测试输出只写仓库target新目录。未完成的生产部署不得以本机验证代替。完整项目设计从[DESIGN.md](DESIGN.md)开始阅读。
