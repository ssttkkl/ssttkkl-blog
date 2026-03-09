---
title: 米家智能设备动作调用实战：从失败到成功
date: 2026-03-10 02:47:00
categories: 技术
tags: [智能家居, Python, 米家, API, 自动化]
---

## 问题背景

在使用米家智能设备时，我遇到了一个有趣的问题：如何通过 API 启动洗衣机？看似简单的需求，实际操作中却遇到了不少坑。

## 初次尝试：设置属性

最开始，我尝试使用 `mijiaapi` CLI 工具设置设备开关：

```bash
uvx mijiaapi set --did 911614522 --prop_name on --value true
```

结果发现洗衣机虽然"开机"了，但只是处于待机状态（status=1），并没有真正开始洗涤。

## 关键发现：动作 vs 属性

通过查看设备完整信息，我发现了问题所在：

```bash
uvx mijiaapi --get_device_info mibx5.washer.32
```

输出显示洗衣机支持三个关键动作（action）：
- **start-wash** (siid=2, aiid=2) - 开始洗涤 ⭐
- **pause** (siid=2, aiid=3) - 暂停
- **stop-washing** (siid=2, aiid=1) - 停止

**核心问题**：启动洗衣机需要调用 `start-wash` 动作，而不是简单设置 `on` 属性。

## 解决方案：使用 Python API

`mijiaapi` CLI 工具只支持 `get` 和 `set` 操作，不支持调用动作。需要使用 Python API：

### 1. 环境准备

使用 `uv` 创建虚拟环境并安装依赖：

```bash
uv venv washer-env
source washer-env/bin/activate
uv pip install mijiaapi
```

### 2. 调用动作

```python
from mijiaapi import MijiaAPI

# 初始化 API（会自动读取已保存的登录信息）
api = MijiaAPI()

# 调用 start-wash 动作
result = api.run_action({
    'did': '911614522',  # 设备 ID
    'siid': 2,           # 服务 ID
    'aiid': 2            # 动作 ID (start-wash)
})

print(result)  # {'message': '成功'}
```

### 3. 成功！

执行后返回 `{'message': '成功'}`，洗衣机成功启动洗涤程序。

## 重要提示

### 动作 ID 不是固定的

不同型号的米家设备，即使是同类设备（如洗衣机），其 `siid` 和 `aiid` 可能不同。**必须先查询设备信息获取正确的动作 ID**：

```bash
uvx mijiaapi --get_device_info <model>
```

从输出中找到对应动作的 `siid` 和 `aiid`，然后使用这些值调用。

### CLI vs Python API

| 功能 | CLI 工具 | Python API |
|------|---------|-----------|
| 查询设备 | ✅ | ✅ |
| 设置属性 | ✅ | ✅ |
| 调用动作 | ❌ | ✅ |

## 总结

- 米家设备的"开机"和"启动"是两个概念
- 复杂操作需要调用动作（action），而非设置属性（property）
- CLI 工具功能有限，复杂场景需要使用 Python API
- 动作 ID 因设备型号而异，需要动态查询

## 参考资源

- [mijiaapi GitHub](https://github.com/Do1e/mijia-api) - 第三方米家 API 库
- 米家设备型号：`mibx5.washer.32`（米家洗衣机 超净洗 滚筒 10kg）
