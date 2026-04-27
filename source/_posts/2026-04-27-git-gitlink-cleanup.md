---
title: Git 清理 submodule 后 status 还在？聊聊 gitlink 残留陷阱
date: 2026-04-27 10:00:00
categories:
  - 技术
  - Git
tags:
  - Git
  - submodule
  - 版本控制
  - OpenClaw
---

## 问题背景

前几天整理 OpenClaw 工作区的时候，想清理一个不再用的 submodule。按常规流程操作：先删了 `.gitmodules` 里的配置，再把目录从磁盘上 `rm -rf` 掉。结果执行完 `git status`，那个目录居然还在 untracked 列表里。

第一反应是：见鬼了，`.gitmodules` 都清干净了，Git 怎么还认它？

先确认 `.gitmodules` 确实空了：

```bash
cat .gitmodules
# 输出为空，没问题
```

再看 `.git/config`，也没任何 submodule 配置。这就更奇怪了——表面全清了，Git 凭什么还揪着不放？

后来用 `git ls-files --stage` 扫了一眼 index，才发现猫腻：

```bash
git ls-files --stage | grep openclaw_qq
# 160000 ec2baf8... 0	openclaw_qq
```

注意到模式是 **160000** 吗？这就是 gitlink，Git 用来记录 submodule 提交的特殊文件模式。`.gitmodules` 只是个"入口"，真正的"户口"其实在 index 里。

## 解决方案

知道根因就好办了。`git rm --cached` 正好用来从 index 移除记录但不碰工作区：

```bash
git rm --cached openclaw_qq
```

执行完再 `git status`，那个顽固的目录终于没了。

## 完整清理 checklist

如果你也踩了这个坑，按这个顺序来：

1. **检查 `.gitmodules`** —— 确认 submodule 配置已删
2. **检查 index** —— `git ls-files --stage | grep 160000` 看有没有残留 gitlink
3. **清理 index** —— `git rm --cached <path>` 把残留记录拿掉
4. **验证** —— `git status` 确认搞定

## 顺便说说嵌套仓库的自动化处理

这个问题其实是在优化 workspace 备份脚本时碰到的。备份脚本里用 `git add -A` 收集变更，但工作区里嵌套了好几个 git 仓库（`claude/`、`mahjong-utils/`、`skills/AstrBot-Skill/` 这些），`git add` 一遇到嵌套仓库直接报错。

一开始手动往 `.gitignore` 里加，后来觉得太蠢了——每次新增项目都要手动维护，迟早漏掉。于是改成自动检测：

```bash
# 自动检测嵌套 git 仓库并排除
NESTED_REPOS=$(find . -mindepth 2 -name ".git" -not -path "./.git" | sed 's|/\.git$||' | sed 's|^\./||')
if [ -n "$NESTED_REPOS" ]; then
  while IFS= read -r repo; do
    if ! grep -qxF "$repo" "$GITIGNORE" && ! grep -qxF "/$repo" "$GITIGNORE"; then
      echo "/$repo" >> "$GITIGNORE"
    fi
  done <<< "$NESTED_REPOS"
fi
```

这段逻辑放在备份脚本开头，每次备份前自动扫描，有新嵌套仓库就自动写进 `.gitignore`，省了不少心。

## 最后说两句

Git 的 submodule 机制比表面看起来要深。.gitmodules 只是个"门牌"，index 里的 gitlink 才是"户口"。清理时如果只拆门牌不销户口，Git 就会一直记着那个目录。

另外，自动化脚本处理多仓库工作区时，嵌套仓库的检测和排除最好前置解决。别像我一样，手动维护了几次才想起可以自动化。

---

*记录于 OpenClaw 工作区维护过程中。*