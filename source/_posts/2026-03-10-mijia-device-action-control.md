---
title: 米家洗衣机API踩坑记：从问题到OpenClaw Skill
date: 2026-03-10 02:47:00
categories: 技术
tags: [智能家居, Python, 米家, API, 自动化, OpenClaw]
---

## 背景

想用API启动米家洗衣机，结果发现没那么简单。最后把整个流程做成了 OpenClaw 的 mijia skill，现在可以直接通过 AI Agent 控制米家设备。

## 第一次尝试

用 `mijiaapi` CLI 工具：

```bash
uvx mijiaapi set --did 911614522 --prop_name on --value true
```

洗衣机确实"开机"了，但只是待机，没开始洗。

## 问题在哪

查了下设备信息：

```bash
uvx mijiaapi --get_device_info mibx5.washer.32
```

发现洗衣机支持三个动作：
- start-wash (siid=2, aiid=2) - 开始洗涤
- pause (siid=2, aiid=3) - 暂停  
- stop-washing (siid=2, aiid=1) - 停止

原来要调用 `start-wash` 动作，不是设置 `on` 属性。

## 解决方法

CLI 工具只支持 `get` 和 `set`，不支持调用动作。得用 Python API。

### 环境准备

```bash
uv venv washer-env
source washer-env/bin/activate
uv pip install mijiaapi
```

### 调用动作

```python
from mijiaapi import MijiaAPI

api = MijiaAPI()

result = api.run_action({
    'did': '911614522',
    'siid': 2,
    'aiid': 2
})

print(result)  # {'message': '成功'}
```

执行后洗衣机开始洗涤。

## 注意

### 动作ID不固定

不同型号的设备，`siid` 和 `aiid` 可能不同。用之前先查：

```bash
uvx mijiaapi --get_device_info <model>
```

### CLI vs Python API

| 功能 | CLI | Python API |
|------|-----|-----------|
| 查询设备 | ✅ | ✅ |
| 设置属性 | ✅ | ✅ |
| 调用动作 | ❌ | ✅ |

## 总结

米家设备的"开机"和"启动"是两回事。复杂操作要调用动作，不是设置属性。CLI工具功能有限，得用Python API。

## OpenClaw Skill

基于这个经验，开发了 [mijia skill](https://github.com/ssttkkl/mijia-skill)，可以通过 OpenClaw AI Agent 直接控制米家设备：

```bash
# 安装技能
npx clawhub@latest install mijia

# 使用示例
"打开暖风机开到22度"
"启动洗衣机"
"查看设备状态"
```

技能特点：
- 自动处理设备查询和动作调用
- 支持多种米家设备（暖风机、洗衣机、香薰机等）
- AI Agent 可以理解自然语言指令

现在不用写代码，直接说话就能控制米家设备了。

## 参考

- [mijiaapi](https://github.com/Do1e/mijia-api) - 第三方米家API库
- 设备型号：mibx5.washer.32
