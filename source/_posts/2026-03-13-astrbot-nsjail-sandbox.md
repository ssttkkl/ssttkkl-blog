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

这篇文章记录了我用 3 天时间，从零开始为 AstrBot 构建 NsJail 沙箱插件的完整过程。这不是一个"Hello World"教程，而是一次真实的工程实践，包含了所有的坑、所有的权衡、所有的思考。

## 为什么需要沙箱？

AstrBot 是一个多平台聊天机器人框架，支持 QQ、Telegram、Discord 等。它的核心特性是 **LLM Function Calling**：让 AI 调用工具完成任务。

但问题来了：
- 用户可能在群聊中让 AI 执行任意命令
- 恶意用户可能尝试攻击服务器
- 不同用户的会话需要隔离

我的选择：**NsJail** - Google 开源的轻量级沙箱工具，基于 Linux Namespace 和 Cgroup，毫秒级启动。


## 第一个坑：Docker 权限与用户命名空间

### 尝试 1：Privileged 模式

```yaml
privileged: true
```

能工作，但安全风险太大。

### 尝试 2：用户命名空间映射

配置 `/etc/subuid` 和 `/etc/subgid`，使用 `--uid_mapping` 和 `--gid_mapping`。

**失败**：`newgidmap: Operation not permitted`

**原因**：Docker 的 `privileged` 模式与用户命名空间不兼容。

### 尝试 3：参数格式错误

**错误写法**：
```python
"--uid_mapping 0:100000:1 --gid_mapping 0:100000:1"
```

**正确写法**：
```python
"--uid_mapping", "0:100000:1",
"--gid_mapping", "0:100000:1"
```

**教训**：命令行参数要严格按照列表格式传递。


### 尝试 4：tmpfs 挂载失败

启用用户命名空间后：

```
mkdir('/tmp/nsjail.0.root//tmp:size=100M'): Permission denied
```

**原因**：映射的 UID 无法在宿主机创建挂载点目录。

**解决**：移除 tmpfs 挂载，使用 chroot 环境内的 /tmp。

### 尝试 5：精细化权限

```yaml
cap_add:
  - SYS_ADMIN
  - NET_ADMIN
security_opt:
  - apparmor=unconfined
  - seccomp=unconfined
```

配合 `--disable_clone_newuser`，使用固定 UID 99999。

**成功**！

### 权衡

放弃用户命名空间，换取：
- 更简单的部署
- 更好的兼容性
- 基本的安全隔离


## 第二个坑：Session ID 特殊字符

### 问题

nsjail 挂载失败，报错不明确。

### 调试

检查 session_id：包含冒号、感叹号等特殊字符（来自 QQ 消息 ID）。

### 解决

```python
clean_session_id = re.sub(r'[^a-zA-Z0-9_-]', '_', session_id)
```

**教训**：文件路径中的特殊字符会导致挂载失败。


## 第三个坑：如何提供软件环境？

### 尝试 1：Chroot 环境

创建 `/nsjail-root`，包含完整的系统目录。

**问题**：
- 占用 1.2GB 磁盘空间
- 需要维护两套软件
- 更新麻烦

### 尝试 2：Bindmount 复用宿主软件

```bash
--bindmount /usr:/usr:ro
--bindmount /lib:/lib:ro
--bindmount /bin:/bin:ro
```

**优势**：
- 零额外空间
- 自动同步更新
- 沙箱内直接使用 Python、Node.js、Git 等

**最终选择**：Bindmount


## 第四个坑：网络隔离的误区

### 误区

以为 `--disable_clone_newnet` 是"禁用网络"。

**实际**：是"不创建网络命名空间"，即共享宿主网络。

### 正确理解

- **不加参数**：创建空的网络命名空间（无网卡，完全断网）
- **加 --disable_clone_newnet**：共享宿主网络

### 最终方案

```python
if enable_network:
    nsjail_args.append("--disable_clone_newnet")
```

默认断网，按需启用。


## 第五个坑：Node.js OOM

### 尝试 1：rlimit_as

```bash
--rlimit_as 512
```

Node.js 崩溃：`JavaScript heap out of memory`

### 问题分析

- `rlimit_as` 限制**虚拟内存**
- V8 引擎初始化需要大量虚拟内存

### 尝试 2：Cgroup V2

```python
"--use_cgroupv2",
"--cgroup_mem_max", str(memory_limit_mb * 1024 * 1024)
```

**成功**！只限制物理内存。


## 第六个坑：majsoul-query 调用问题

### 问题

majsoul-query 使用 uv 管理依赖，在沙箱中调用失败。

### 尝试 1：uv run

```bash
uv run majsoul-query info "玩家"
```

**失败**：`Could not detect glibc version`

### 尝试 2：虚拟环境 Python

```bash
.venv/bin/python -m majsoul_query.cli
```

**失败**：`execve failed: No such file or directory`

**原因**：符号链接在 nsjail 中执行失败。

### 尝试 3：PYTHONPATH

```bash
PYTHONPATH=/path/to/.venv/lib/python3.12/site-packages python3 -m majsoul_query.cli
```

**成功**！复用只读目录的虚拟环境。


## 第七个坑：如何发送沙箱内的图片？

### 问题

沙箱内路径：`/workspace/chart.png`
真实路径：`/tmp/nsjail_session123_abc/chart.png`

### 解决方案

```python
@filter.llm_tool(name="send_sandbox_image")
async def send_sandbox_image(self, event, image_path: str):
    sandbox_dir = self.sandbox_mgr.sandboxes[session_id]['dir']
    real_path = os.path.join(sandbox_dir, image_path[11:])
    yield event.image_result(real_path)
```

路径转换工具，让 LLM 能发送沙箱内生成的文件。


## 最终架构

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

### NsJail 配置

```bash
--user 99999 --group 99999
--disable_clone_newuser
--bindmount /usr:/usr:ro
--bindmount /lib:/lib:ro
--bindmount /bin:/bin:ro
--bindmount /tmp:/tmp:rw
--bindmount {sandbox_dir}:/workspace:rw
--use_cgroupv2
--cgroup_mem_max {memory_limit}
```


### 测试结果

| 类别 | 通过率 |
|------|--------|
| 文件操作 | 100% |
| 安全隔离 | 87.5% |
| Python | 81% |
| Shell | 75% |

## 关键经验

### 1. 不要害怕失败

每个方案都是在前一个失败的基础上改进的。

### 2. 理解工具的本质

- `--disable_clone_newnet` 不是"禁用网络"
- `rlimit_as` 限制虚拟内存，不是物理内存

### 3. 权衡是必然的

- 放弃用户命名空间 → 换取简单部署
- Bindmount 复用软件 → 放弃完全隔离

## 总结

最终的方案不是最完美的，但是最适合当前场景的。

---

**项目地址**：https://github.com/ssttkkl/astrbot-plugin-nsjail

