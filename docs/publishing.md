# 发布指南

这是独立仓库，根目录为 flowtrail-server/。原始目录位置不会影响构建或运行。默认单用户本地使用，不应直接暴露到公网。

## 上传源码

1. 运行 `mvn clean verify` 和 `python scripts/smoke.py`。
2. 检查 `git status --short`，确认不包含数据、密钥、构建缓存或个人文件。
3. 用自己的 Git 身份创建实际提交；不要虚构开发历史。
4. 在 GitHub 创建空仓库，通过该页面给出的地址设置 origin，再推送本地分支。

```sh
git add .
git diff --cached --stat
git commit -m "feat: build independent flowtrail-server learning service"
git branch -M main
```

执行 GitHub 页面提供的 `git remote add origin ...` 与 `git push -u origin main`。初次上传后检查 Windows/Linux CI 均通过。

## 发行版本

更新 pom.xml 版本，确认验证通过后提交。推送相同版本的标签（如 v0.1.0）触发发行工作流。ZIP 包包含可执行JAR、启动器、示例、文档和依赖许可，运行时仅需JDK21。

`.env.example` 仅说明环境变量，不会被启动器自动加载。使用 shell 或 IDE 配置变量，勿上传真实密钥。数据位于启动器项目目录下的 data/，退出与升级时保留该目录。

初始实现由AI编程助手协助完成。学习展示应说明自己实际理解、复现和改进的内容，不把未来计划或未执行的远端检查写成完成事实。

