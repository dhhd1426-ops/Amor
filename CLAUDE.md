# 给 Claude 的说明

动手前按顺序读：

1. [`claude-handoff/问题指南.md`](claude-handoff/问题指南.md)：做事规矩、真出过的问题和避开的办法、发安装包的步骤。**必读。**
2. [`HANDOFF.md`](HANDOFF.md)、[`claude-handoff/NATIVE-APP.md`](claude-handoff/NATIVE-APP.md)：项目来历和原生 App 说明。

最新代码在 `check` 分支，不在默认分支 `setup`：先 `git fetch origin check`，在它最新的提交上改。

固定顺序：页面或动效改动先做网页原型 → 用户同意后改代码 → 推 `check` 编译 → 通过后发 Release 安装包直链 → 会话结束前把新问题补进问题指南。回复用中文。
