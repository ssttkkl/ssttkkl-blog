---
title: NoneBot2插件转OpenClaw Skill实战
date: 2026-03-10 03:02:00
categories: 技术
tags: [NoneBot2, OpenClaw, Python, 插件开发, 转换]
---

## 背景

NoneBot2 是流行的 Python 聊天机器人框架，但插件只能在 NoneBot2 环境运行。如果想把插件逻辑提取出来，做成独立的 CLI 工具或 AI Agent 技能，就需要转换。

## 核心思路

NoneBot2 插件 = 命令处理器 + 消息响应
OpenClaw Skill = CLI 脚本 + 核心逻辑

转换就是：
1. 提取命令处理逻辑
2. 去掉 NoneBot2 依赖
3. 改成 CLI 参数
4. 保留异步代码

## 识别 NoneBot2 项目

特征：
- `pyproject.toml` 里有 `nonebot2`
- 代码里有 `from nonebot import`
- 用了 `on_command`、`on_message` 等装饰器

## 转换步骤

### 1. 提取命令

NoneBot2 的命令模式：

```python
from nonebot import on_command
from nonebot.params import CommandArg

ping = on_command("ping")

@ping.handle()
async def handle_ping(args: Message = CommandArg()):
    msg = args.extract_plain_text()
    await ping.finish(f"Pong! {msg}")
```

### 2. 转成 CLI

```python
import argparse
import asyncio

async def ping(message=None):
    print(f"Pong! {message}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("message", nargs="?")
    args = parser.parse_args()
    asyncio.run(ping(args.message))

if __name__ == "__main__":
    main()
```

### 3. 关键转换

| NoneBot2 | CLI |
|----------|-----|
| `CommandArg()` | `parser.add_argument()` |
| `await matcher.finish()` | `print()` |
| `await matcher.send()` | `print()` |
| `Event.get_user_id()` | 命令行参数 |

### 4. 保留异步

不要把 async 改成同步！用 `asyncio.run()` 包装：

```python
def main():
    parser = argparse.ArgumentParser()
    args = parser.parse_args()
    asyncio.run(async_handler(args))
```

为什么？
- 避免重写 httpx/aiohttp 代码
- 保留原有逻辑
- 标准 Python 模式

## 实战案例：雀魂查询插件

原插件：`nonebot-plugin-majsoul`

**原代码**：
```python
four_player_majsoul_info_matcher = on_command('majsoul_info')

@four_player_majsoul_info_matcher.handle()
async def handle(event: Event):
    args = event.get_message().extract_plain_text().split()
    nickname = args[1]
    result = await query_api(nickname)
    await matcher.finish(result)
```

**转换后**：
```python
async def majsoul_info(nickname: str, three: bool = False):
    result = await query_api(nickname, three)
    print(result)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("nickname")
    parser.add_argument("--three", action="store_true")
    args = parser.parse_args()
    asyncio.run(majsoul_info(args.nickname, args.three))
```

**使用**：
```bash
python majsoul-info.py PlayerName
python majsoul-info.py PlayerName --three
```

## 技能结构

```
skill-name/
├── SKILL.md
├── scripts/
│   ├── cmd1.py
│   └── cmd2.py
├── src/
│   ├── api.py
│   └── utils.py
└── pyproject.toml
```

## 包管理

必须用包管理器，推荐 uv：

```toml
[project]
name = "skill-name"
dependencies = ["httpx>=0.24.0"]

[project.scripts]
cmd1 = "scripts.cmd1:main"
```

```bash
uv sync
uv run python scripts/cmd1.py
```

## 注意事项

1. **去掉 NoneBot2 导入**：删除所有 `from nonebot import`
2. **保留异步**：用 `asyncio.run()` 包装
3. **简化输出**：`await matcher.finish()` → `print()`
4. **移除装饰器**：删除 `@handle_error` 等
5. **参数转换**：`CommandArg()` → `argparse`

## 不适合转换的

- `on_notice()`、`on_request()` - 平台特定事件
- 需要持续运行的 WebSocket 连接
- 复杂的多步交互流程

这些需要保留为 NoneBot2 插件或重新设计。

## 总结

NoneBot2 插件转 CLI 的核心是：
- 提取业务逻辑
- 去掉框架依赖
- 保留异步代码
- 改用标准参数解析

转换后的代码更通用，可以在任何 Python 环境运行。
