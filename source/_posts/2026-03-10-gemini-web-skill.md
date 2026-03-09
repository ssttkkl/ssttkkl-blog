---
title: 开发Gemini Web技能：OpenClaw调用Gemini免费生图
date: 2026-03-10 03:24:00
categories: 技术
tags: [OpenClaw, AI, Gemini, 图像生成, Skill开发]
---

## 需求

想让 AI Agent 能生图，但官方 API 要么收费要么有限制。Gemini Web 版可以免费用 Imagen 3，能不能通过 OpenClaw 直接调用？

## 方案

基于开源库 [gemini-webapi](https://github.com/HanaokaYuzu/Gemini-API)，开发 `gemini-web` 技能，封装 CLI 工具让 OpenClaw 能直接调用。

## 技能结构

```
gemini-web/
├── SKILL.md              # 技能文档
├── scripts/              # CLI 封装脚本
├── pyproject.toml        # uv 依赖管理
└── src/                  # 核心逻辑
```

## 核心功能

### 1. Cookie 管理

从 Chrome 自动获取登录 Cookie：

```bash
uv run python3 -c "
import browser_cookie3
import json

cj = browser_cookie3.chrome(domain_name='google.com')
cookies = list(cj)

psid = [c for c in cookies if c.name == '__Secure-1PSID'][0].value
psidts = [c for c in cookies if c.name == '__Secure-1PSIDTS'][0].value

config = {'secure_1psid': psid, 'secure_1psidts': psidts}
with open('~/.config/gemini-web/cookies.json', 'w') as f:
    json.dump(config, f)
"
```

### 2. 图像生成

```bash
cd ~/.openclaw/workspace/skills/gemini-web
uv run gemini-web generate "画一只雪中独狼" \
  --image-output ~/Library/Application\ Support/gemini-web/images/
```

生成的图片自动保存到指定目录。

### 3. 文本对话

```bash
uv run gemini-web generate "解释量子计算"
```

### 4. 文件分析

```bash
uv run gemini-web generate "描述这张图片" --file photo.jpg
```

## OpenClaw 集成

直接对 AI Agent 说：

```
"生成图片：赛博朋克城市夜景"
```

Agent 会：
1. 识别生图需求
2. 调用 gemini-web 技能
3. 执行 CLI 命令
4. 获取生成的图片
5. 通过 message 工具发送给用户

## 关键技术

### Cookie 自动刷新

技能默认启用 Cookie 自动刷新，无需手动维护。

**最佳实践**：
1. 用 Chrome 隐私模式登录 Gemini
2. 获取 Cookie
3. 立即关闭隐私窗口
4. Cookie 可用数周

### 模型选择

支持多个 Gemini 模型：
- `gemini-3.0-flash` - 快速（默认）
- `gemini-3.0-pro` - 最强
- `gemini-3.0-flash-thinking` - 思维链

### 会话管理

```bash
# 列出会话
uv run gemini-web session list

# 查看历史
uv run gemini-web session history session-xxx
```

## 使用示例

### 生图并发送

```python
# Agent 内部流程
result = exec("cd ~/.openclaw/workspace/skills/gemini-web && "
              "uv run gemini-web generate '画一只橘猫' "
              "--image-output ~/images/")

# 发送图片
message(action="send", 
        target="user", 
        media="~/images/generated.png")
```

## 效果

- 速度：约 60 秒/张
- 质量：Imagen 3 水平
- 成本：完全免费
- 模型：支持 Gemini 3.0 系列

## 注意

1. 需要先在 Chrome 登录 Gemini
2. 建议用隐私模式获取 Cookie
3. Cookie 自动刷新，但偶尔需要重新登录
4. 仅供个人学习使用

## 总结

通过封装 gemini-webapi CLI 工具，实现了 OpenClaw 免费调用 Gemini 生图和对话。配合 Agent，现在可以直接说话生成图片。

## 参考

- [gemini-web skill](https://clawhub.com/skills/gemini-web)
- [gemini-webapi](https://github.com/HanaokaYuzu/Gemini-API) - 开源逆向库
- Gemini: https://gemini.google.com/
