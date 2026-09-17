# 验证记录

验证日期：2026-09-16。环境：Windows、OpenJDK 21.0.1、Maven 3.8.5。

## 已执行

- `mvn -B -ntp clean verify`：BUILD SUCCESS，25 个测试，0 失败、0 错误、0 跳过；包含 Spotless 与 JDK/Maven 版本检查。
- `python scripts/smoke.py`：解压真实 ZIP、启动其中的 JAR，验证首页及 JavaScript 资源、DAG 校验、中文输入传递、执行输出、缺失输入、404，以及终止服务后重新启动并读取原工作流、运行和历史。测试通过，临时进程已停止。
- 运行时 41 个原始依赖 JAR 的许可资源已提取到 `licenses/`，通过 `python scripts/collect_licenses.py --check` 校验清单和资源内容。
- JavaScript、Python、XML、PowerShell 与 Shell 脚本语法检查通过；另进行了独立源码审查。

## 发行验证发现并修复的问题

首次在 Windows 直接终止进程后，运行记录读取返回 404。确认启动前后数据库路径一致，加入 `WRITE_DELAY=0` 的对照测试通过。随后将该参数加入正式默认配置，冒烟脚本改为直接使用发行包默认数据库配置，完整构建和重启测试再次通过。

H2 默认会延迟写入已提交事务；这里关闭该延迟，以支持本地演示的立即停止、重启流程。这不构成断电或硬件故障下的数据持久性保证。参见 [H2 WRITE_DELAY](https://h2database.com/html/commands.html#set_write_delay)。

## 尚未验证

- 当前自动化环境没有可用浏览器，未执行浏览器点击或视觉验收。可按 README 的示例流程手动检查页面。
- 已配置 Windows/Linux GitHub Actions，但尚未推送远端，因此不把远端 CI 或 Linux 实机验证视为已通过。

`target/smoke-report.json` 和 `target/surefire-reports/` 是本次构建的本地证据，属于忽略的构建产物。可随时重新运行上述命令生成自己的记录。
