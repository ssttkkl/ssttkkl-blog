---
title: 构建 Skill-First Agent：基于 AstrBot 和 NsJail 的安全沙箱实践
date: 2026-03-13 21:48:00
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


## 第一个问题：Docker 权限

### 尝试 1：最简单的方案

```yaml
services:
  astrbot:
    privileged: true
```

能工作，但安全风险太大。

### 尝试 2：精细化权限

```yaml
cap_add:
  - SYS_ADMIN
  - NET_ADMIN
security_opt:
  - apparmor=unconfined
  - seccomp=unconfined
```

启动 nsjail，报错：

```bash
[E] clone(flags=CLONE_NEWUSER) failed: Operation not permitted
```

**原因**：Docker 的 `privileged` 模式与用户命名空间不兼容。

### 解决方案：禁用用户命名空间

```bash
--disable_clone_newuser
--user 99999 --group 99999
```

**权衡**：放弃用户命名空间，换取更简单的部署。


## 第二个问题：如何提供软件环境？

### 尝试 1：创建 Chroot 环境

创建一个完整的 chroot 环境，包含所有系统目录。

**问题**：
- 占用 1.2GB 磁盘空间
- 需要维护两套软件
- 更新麻烦

### 尝试 2：Bindmount 复用宿主软件

突然意识到：为什么不直接复用宿主的软件？

```bash
--bindmount /usr:/usr:ro
--bindmount /lib:/lib:ro
--bindmount /bin:/bin:ro
--bindmount {sandbox_dir}:/workspace:rw
```

**优势**：
- 零额外空间
- 自动同步更新
- 沙箱内直接使用 Python、Node.js、Git 等

**最终方案**：Bindmount


## 第三个问题：网络隔离

### 尝试 1：使用 --disable_clone_newnet

以为这样就能"禁用网络"。

**实际效果**：共享宿主网络，完全没有隔离。

### 正确理解

- **不加参数**：创建空的网络命名空间（无网卡，完全断网）
- **加 --disable_clone_newnet**：共享宿主网络

### 最终方案

```python
if enable_network:
    nsjail_args.append("--disable_clone_newnet")
    nsjail_args.extend(["--bindmount", "/etc/resolv.conf:/etc/resolv.conf:ro"])
# 否则：默认创建隔离的网络命名空间
```


## 第四个问题：Node.js OOM

### 尝试 1：使用 rlimit_as 限制内存

```bash
--rlimit_as 512  # 限制 512MB
```

Node.js 直接崩溃：

```
# Allocation failed - JavaScript heap out of memory
```

### 问题分析

- `--rlimit_as` 限制的是**虚拟内存**
- V8 引擎初始化需要大量虚拟内存（JIT、GC）
- 即使实际使用很少物理内存，虚拟内存限制也会导致失败

### 尝试 2：Cgroup V2 限制物理内存

```python
nsjail_args.extend([
    "--use_cgroupv2",
    "--cgroup_mem_max", str(memory_limit_mb * 1024 * 1024)
])
```

**关键区别**：
- `rlimit_as`：限制虚拟内存（地址空间）
- `cgroup_mem_max`：限制物理内存（RSS）

**最终方案**：Cgroup V2，允许虚拟内存自由分配。


## 第五个问题：如何发送沙箱内的图片？

### 问题

沙箱内路径：`/workspace/chart.png`
真实路径：`/tmp/nsjail_session123_1234567890_abc/chart.png`

LLM 只知道沙箱路径，如何发送？

### 解决方案：路径转换工具

```python
@filter.llm_tool(name="send_sandbox_image")
async def send_sandbox_image(self, event, image_path: str):
    session_id = event.session_id or "default"
    sandbox_dir = self.sandbox_mgr.sandboxes[session_id]['dir']
    
    if image_path.startswith('/workspace/'):
        real_path = os.path.join(sandbox_dir, image_path[11:])
    
    yield event.image_result(real_path)
```

**工作流程**：
1. LLM 在沙箱中生成图片
2. LLM 调用 `send_sandbox_image("/workspace/chart.png")`
3. 插件转换为真实路径并发送


## 最终架构

经过多次尝试和调整，最终的方案：

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
--bindmount /sandbox-cache:/sandbox-cache:rw
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

每个方案都是在前一个失败的基础上改进的。Chroot → Bindmount，rlimit_as → Cgroup V2，都是通过试错找到的。

### 2. 理解工具的本质

- `--disable_clone_newnet` 不是"禁用网络"，而是"不创建网络命名空间"
- `rlimit_as` 限制的是虚拟内存，不是物理内存
- 理解这些细节才能做出正确的决策

### 3. 权衡是必然的

- 放弃用户命名空间 → 换取简单部署
- Bindmount 复用软件 → 放弃完全隔离
- 每个决策都是在"安全"和"可用"之间找平衡

## 总结

这 3 天的开发让我深刻体会到：**技术不是目的，解决问题才是**。

最终的方案不是最完美的，但是最适合当前场景的。它在安全性、性能、可维护性之间找到了一个平衡点。

---

**项目地址**：https://github.com/ssttkkl/astrbot-plugin-nsjail

