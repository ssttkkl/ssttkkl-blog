---
title: 构建 Skill-First Agent：基于 AstrBot 和 NsJail 的安全沙箱实践
date: 2026-03-13 21:55:00
categories:
  - 技术
  - AI Agent
tags:
  - AstrBot
  - NsJail
  - Docker
  - 沙箱
  - LLM
---

## 前言

在 AI Agent 的世界里，大多数人关注的是"如何让 LLM 更聪明"，但我想分享一个不同的视角：**如何让 Agent 更安全、更实用**。

这篇文章记录了我用 3 天时间，从零开始为 AstrBot 构建 NsJail 沙箱插件的完整过程。

## 为什么需要沙箱？

AstrBot 是一个多平台聊天机器人框架，支持 QQ、Telegram、Discord 等。它的核心特性是 **LLM Function Calling**：让 AI 调用工具完成任务。

但问题来了：
- 用户可能在群聊中让 AI 执行任意命令
- 恶意用户可能尝试攻击服务器
- 不同用户的会话需要隔离

我的选择：**NsJail** - Google 开源的轻量级沙箱工具。


## 第一版：最简单的实现

### 初始配置

**Docker 配置**：
```yaml
privileged: true
```

**NsJail 参数**：
```python
nsjail_cmd = [
    "nsjail",
    "--mode", "o",
    "--chroot", "/nsjail-root",
    "--user", "99999",
    "--group", "99999",
    "--bindmount", f"{sandbox_dir}:/workspace:rw",
    "--cwd", "/workspace",
    "--time_limit", "60",
    "--rlimit_as", "512",
    "--rlimit_fsize", "100",
    "--",
    "/bin/bash", "-c", command
]
```

**能工作**，但有明显问题：
- `privileged: true` 安全风险大
- Chroot 环境占用 1.2GB
- 没有网络隔离
- 没有资源限制


## 然后开始踩坑

### 坑 1：想要更好的安全性

尝试用户命名空间：

```python
"--uid_mapping", "0:100000:1",
"--gid_mapping", "0:100000:1",
```

**失败**：`newgidmap: Operation not permitted`

**原因**：Docker `privileged` 模式与用户命名空间冲突。

**解决**：放弃用户命名空间，改用 `--disable_clone_newuser`。


### 坑 2：Session ID 特殊字符

QQ 消息 ID 包含冒号、感叹号，导致挂载失败。

**解决**：
```python
clean_session_id = re.sub(r'[^a-zA-Z0-9_-]', '_', session_id)
```

### 坑 3：Chroot 太重

1.2GB 的 chroot 环境，维护麻烦。

**解决**：改用 Bindmount 复用宿主软件。

```python
"--bindmount", "/usr:/usr:ro",
"--bindmount", "/lib:/lib:ro",
"--bindmount", "/bin:/bin:ro",
```


### 坑 4：网络隔离误区

以为 `--disable_clone_newnet` 是"禁用网络"，实际是"共享宿主网络"。

**正确理解**：
- 不加参数 = 创建空网络命名空间（完全断网）
- 加参数 = 共享宿主网络

### 坑 5：Node.js OOM

`--rlimit_as 512` 限制虚拟内存，V8 引擎初始化失败。

**解决**：改用 Cgroup V2 限制物理内存。

```python
"--use_cgroupv2",
"--cgroup_mem_max", str(memory_limit_mb * 1024 * 1024)
```


### 坑 6：majsoul-query 调用失败

**尝试 1**：`uv run` → glibc 检测失败
**尝试 2**：`.venv/bin/python` → 符号链接执行失败
**尝试 3**：`PYTHONPATH` → 成功！

```bash
PYTHONPATH=/path/.venv/lib/python3.12/site-packages python3 -m majsoul_query.cli
```

### 坑 7：如何发送沙箱内的图片？

LLM 只知道沙箱路径 `/workspace/chart.png`，需要转换为真实路径。

**解决**：
```python
@filter.llm_tool(name="send_sandbox_image")
async def send_sandbox_image(self, event, image_path: str):
    real_path = os.path.join(sandbox_dir, image_path[11:])
    yield event.image_result(real_path)
```


## 最终配置

### Docker 配置

```yaml
cap_add:
  - SYS_ADMIN
  - NET_ADMIN
security_opt:
  - apparmor=unconfined
  - seccomp=unconfined
volumes:
  - /sys/fs/cgroup:/sys/fs/cgroup:rw
  - ./sandbox-cache:/sandbox-cache
```

### NsJail 完整参数

```python
nsjail_cmd = [
    "nsjail",
    "--mode", "o",
    "--user", "99999",
    "--group", "99999",
    "--disable_clone_newuser",
    "--bindmount", "/usr:/usr:ro",
    "--bindmount", "/lib:/lib:ro",
    "--bindmount", "/bin:/bin:ro",
    "--bindmount", "/tmp:/tmp:rw",
    "--bindmount", "/sandbox-cache:/sandbox-cache:rw",
    "--bindmount", f"{sandbox_dir}:/workspace:rw",
    "--cwd", "/workspace",
    "--time_limit", "60",
    "--rlimit_fsize", "100",
    "--use_cgroupv2",
    "--cgroup_mem_max", str(memory_limit_mb * 1024 * 1024),
    "--env", "PATH=/usr/local/bin:/usr/bin:/bin",
    "--env", "HOME=/workspace",
    "--env", "UV_CACHE_DIR=/sandbox-cache/uv",
]

# 网络配置
if enable_network:
    nsjail_cmd.append("--disable_clone_newnet")
    nsjail_cmd.extend([
        "--bindmount", "/etc/resolv.conf:/etc/resolv.conf:ro"
    ])

nsjail_cmd.extend(["--", "/bin/bash", "-c", command])
```


### 测试结果

| 类别 | 通过率 |
|------|--------|
| 文件操作 | 100% |
| 安全隔离 | 87.5% |
| Python | 81% |
| Shell | 75% |

## 关键经验

### 1. 从简单开始

第一版虽然有问题，但能工作。然后逐步优化。

### 2. 理解工具本质

- `--disable_clone_newnet` 不是"禁用网络"
- `rlimit_as` 限制虚拟内存，不是物理内存

### 3. 权衡是必然的

- 放弃用户命名空间 → 换取简单部署
- Bindmount 复用软件 → 放弃完全隔离

## 总结

最终方案不是最完美的，但是最适合当前场景的。

---

**项目地址**：https://github.com/ssttkkl/astrbot-plugin-nsjail

