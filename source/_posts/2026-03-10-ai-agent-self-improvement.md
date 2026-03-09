---
title: AI Agent自我改进机制实战
date: 2026-03-10 02:58:00
categories: 技术
tags: [AI, Agent, 自动化, 学习系统]
---

## 背景

AI Agent 执行任务时会犯错，但每次都重复同样的错误就没意义了。需要一个机制让它记录错误、总结经验、避免重蹈覆辙。

这个机制来自 OpenClaw 的 [self-improving-agent](https://github.com/cyanheads/self-improving-agent) 技能。

## 设计思路

用文件系统当"记忆"：
- `.learnings/LEARNINGS.md` - 记录学习点
- `.learnings/ERRORS.md` - 记录错误
- `.learnings/FEATURE_REQUESTS.md` - 记录功能需求

重要的学习点会"提升"到工作区文件：
- `SOUL.md` - 行为模式
- `AGENTS.md` - 工作流改进
- `TOOLS.md` - 工具使用经验

## 实现

### 1. 记录学习点

```markdown
## LRN-20260309-001: 换方案前需确认

**Date**: 2026-03-09
**Priority**: critical
**Status**: pending

### Context
用户说"帮我完成吧"后，遇到问题时我直接换了方案，没征求同意。

### Learning
遇到问题时应该：
1. 停下来
2. 说明问题
3. 提出2-3个备选方案
4. 等用户确认
5. 执行选定方案

### Action
更新 SOUL.md 的决策流程部分。
```

### 2. 提升到工作区

学习点广泛适用时，提升到主文件：

```bash
# 从 .learnings/LEARNINGS.md 提取
# 精简成规则
# 添加到 SOUL.md 或 AGENTS.md
```

### 3. 标记状态

```markdown
**Status**: promoted → SOUL.md
```

## 触发时机

**记录到 LEARNINGS.md**：
- 用户纠正（"不对，应该是..."）
- 发现更好的方法
- 知识过时

**记录到 ERRORS.md**：
- 命令执行失败
- API 调用错误
- 意外情况

**记录到 FEATURE_REQUESTS.md**：
- 用户要求不存在的功能

## 实际案例

### 案例1：确认流程

**问题**：用户说"帮我完成"后，我在中途多次问"要继续吗"

**学习**：
- 收到明确指令后执行到底
- 不在中途反复确认
- 遇到失败才停下来说明情况

**提升**：更新到 `SOUL.md` 的决策流程

### 案例2：Docker 代理协议

**问题**：尝试给 Docker 配置 `socks5://` 代理失败

**学习**：Docker 的 `HTTP_PROXY` 不支持 SOCKS5，只支持 HTTP/HTTPS

**提升**：记录到 `TOOLS.md` 的 Docker 部分

## 关键点

1. **及时记录**：错误发生时立即记录，别等
2. **结构化**：用固定格式，方便后续检索
3. **定期回顾**：每隔几天回顾 `.learnings/`，提升有价值的内容
4. **删除过时**：已修复的问题标记为 `resolved`

## 效果

- 避免重复错误
- 积累领域知识
- 工作流持续优化
- 形成"肌肉记忆"

## OpenClaw Hook 集成

可以通过 OpenClaw 的 hook 机制自动触发学习记录：

### 配置 Hook

在 `~/.openclaw/hooks/self-improvement/` 创建 hook：

```json
{
  "name": "self-improvement",
  "trigger": "on_message",
  "action": "send_message",
  "message": "Review recent errors and learnings. Record any patterns to .learnings/"
}
```

### 自动触发

Hook 会在以下时机自动提醒：
- 会话开始时
- 执行失败后
- 用户纠正时

这样 Agent 会主动检查并记录学习点，不需要手动触发。

## 参考

- [self-improving-agent](https://github.com/cyanheads/self-improving-agent) - GitHub 原始技能
- OpenClaw Hooks 文档
