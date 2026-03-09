---
title: uv：更快的Python包管理工具
date: 2026-03-10 02:59:00
categories: 技术
tags: [Python, 包管理, uv, 工具]
---

## 什么是uv

uv 是用 Rust 写的 Python 包管理工具，比 pip 快很多。

## 安装

```bash
# macOS/Linux
curl -LsSf https://astral.sh/uv/install.sh | sh

# 或用 Homebrew
brew install uv
```

## 基本用法

### 创建虚拟环境

```bash
uv venv myenv
source myenv/bin/activate
```

### 安装包

```bash
# 单个包
uv pip install requests

# 从 requirements.txt
uv pip install -r requirements.txt

# 指定版本
uv pip install "django>=4.0"
```

### 运行脚本

不用激活环境，直接跑：

```bash
uv run python script.py
uv run pytest
```

## 实际案例：米家API

在米家智能家居控制中用 uv：

```bash
# 创建环境
uv venv washer-env

# 安装依赖
uv pip install mijiaapi

# 运行脚本
uv run python control_washer.py
```

## 为什么快

- 用 Rust 写的，编译后执行
- 并行下载和安装
- 更好的依赖解析算法
- 缓存机制

## 对比

| 操作 | pip | uv |
|------|-----|-----|
| 安装 Django | ~10s | ~2s |
| 创建虚拟环境 | ~3s | <1s |
| 解析依赖 | 慢 | 快 |

## 兼容性

完全兼容 pip，可以直接替换：

```bash
# 原来
pip install package

# 现在
uv pip install package
```

## 注意

1. 还在快速迭代，API 可能变
2. 某些复杂依赖可能有问题
3. 遇到问题可以回退到 pip

## 总结

uv 速度快、兼容 pip、值得试试。特别适合 CI/CD 环境和频繁安装依赖的场景。
