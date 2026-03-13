---
title: 构建 Skill-First Agent：基于 AstrBot 和 NsJail 的安全沙箱实践
date: 2026-03-13 20:50:00
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

**核心理念**：Skill-First Agent
- Agent 的能力来自 Skills，而非模型本身
- Skills 需要安全的执行环境
- 私聊与群聊场景需要不同的设计


## 为什么需要沙箱？

AstrBot 是一个多平台聊天机器人框架，支持 QQ、Telegram、Discord 等。它的核心特性是 **LLM Function Calling**：让 AI 调用工具完成任务。

但问题来了：
- 用户可能在群聊中让 AI 执行任意命令
- 恶意用户可能尝试攻击服务器
- 不同用户的会话需要隔离

传统方案的问题：
1. **白名单命令** - 太受限，无法应对复杂需求
2. **Docker 容器** - 太重，每个会话一个容器不现实
3. **虚拟机** - 更重，启动慢

我的选择：**NsJail** - Google 开源的轻量级沙箱工具
- 基于 Linux Namespace 和 Cgroup
- 毫秒级启动
- 细粒度资源控制
- 每个会话独立沙箱


## 第一天：Docker 权限优化

### 初始方案：简单粗暴

最开始，我用了最简单的方案：

```yaml
services:
  astrbot:
    privileged: true  # 给容器所有权限
```

这能工作，但问题很明显：安全风险太大，违背了最小权限原则。

### 优化：精细化权限

Docker 的 `privileged` 模式本质上是给容器所有 Linux Capabilities。我需要的只是其中几个：

```yaml
cap_add:
  - SYS_ADMIN  # 挂载命名空间
  - NET_ADMIN  # 网络命名空间
security_opt:
  - apparmor=unconfined
  - seccomp=unconfined
```

### 第一个坑：用户命名空间

```bash
[E] clone(flags=CLONE_NEWUSER) failed: Operation not permitted
```

**原因**：Docker 的 `privileged` 模式与用户命名空间不兼容。

**解决方案**：
```bash
--disable_clone_newuser  # 禁用用户命名空间
--user 99999 --group 99999  # 直接指定非 root 用户
```

这是第一个重要的权衡：**放弃用户命名空间，换取更简单的部署**。


## 第二天：架构选择 - Bindmount vs Chroot

### 最初的想法：Chroot 环境

我最开始的方案是创建一个完整的 chroot 环境，占用 1.2GB 磁盘空间，需要维护两套软件。

### 转折点：Bindmount 方案

突然意识到：**为什么不直接复用宿主的软件？**

```bash
--bindmount /usr:/usr:ro
--bindmount /lib:/lib:ro
--bindmount /bin:/bin:ro
--bindmount {sandbox_dir}:/workspace:rw
```

**优势**：
- 零额外空间占用
- 自动同步宿主软件更新
- 沙箱内可以直接使用宿主的 Python、Node.js、Git 等所有软件

### 第二个坑：网络隔离的误区

最开始我用了 `--disable_clone_newnet`，以为这样就能"禁用网络"。

**实际效果**：共享宿主网络，完全没有隔离。

**正确方案**：
- 默认：创建空的网络命名空间（无网卡，完全断网）
- 需要网络时：才加 `--disable_clone_newnet` 共享宿主网络


## 第三天：Node.js OOM 问题

### 第三个坑：Node.js 无法启动

使用 `--rlimit_as 512` 限制内存后，Node.js 直接崩溃：

```
# Allocation failed - JavaScript heap out of memory
```

**原因分析**：
- `--rlimit_as` 限制的是**虚拟内存**（Virtual Memory）
- V8 引擎初始化时需要大量虚拟内存（用于 JIT、GC 等）
- 即使实际使用很少物理内存，虚拟内存限制也会导致初始化失败

### 解决方案：Cgroup V2

```python
if memory_limit_mb > 0:
    nsjail_args.extend([
        "--use_cgroupv2",
        "--cgroup_mem_max", str(memory_limit_mb * 1024 * 1024)
    ])
```

**关键区别**：
- `rlimit_as`：限制虚拟内存（地址空间）
- `cgroup_mem_max`：限制物理内存（RSS）

Cgroup V2 只限制实际使用的物理内存，允许虚拟内存自由分配。


## LLM 工具设计：图片发送

### 问题：沙箱路径 vs 真实路径

沙箱内生成的文件路径：`/workspace/chart.png`
真实路径：`/tmp/nsjail_session123_1234567890_abc/chart.png`

LLM 只知道沙箱路径，如何发送文件？

### 解决方案：路径转换工具

```python
@filter.llm_tool(name="send_sandbox_image")
async def send_sandbox_image(self, event: AstrMessageEvent, image_path: str):
    session_id = event.session_id or "default"
    sandbox_dir = self.sandbox_mgr.sandboxes[session_id]['dir']
    
    # 转换路径
    if image_path.startswith('/workspace/'):
        real_path = os.path.join(sandbox_dir, image_path[11:])
    
    # 发送图片
    yield event.image_result(real_path)
```

**工作流程**：
1. LLM 在沙箱中生成图片
2. LLM 调用 `send_sandbox_image("/workspace/chart.png")`
3. 插件转换为真实路径并发送


## 会话隔离与清理

### 每个会话独立沙箱

```python
def create_sandbox(self, session_id: str):
    timestamp = int(time.time())
    sandbox_dir = tempfile.mkdtemp(prefix=f'nsjail_{session_id}_{timestamp}_')
    self.sandboxes[session_id] = {
        'dir': sandbox_dir,
        'created_at': timestamp
    }
```

**设计要点**：
- 每个 QQ 用户/群聊有独立沙箱
- 同一会话内多次命令共享沙箱
- 会话结束后自动销毁

### 定时清理机制

```python
async def _cleanup_loop(self):
    while True:
        await asyncio.sleep(600)  # 10分钟
        self.sandbox_mgr.cleanup_old_sandboxes()  # 清理超过3天的沙箱
```

## 测试结果

| 类别 | 通过率 | 说明 |
|------|--------|------|
| 文件操作 | 100% | 完美隔离 |
| 安全隔离 | 87.5% | 需加强进程数限制 |
| Python | 81% | 大部分功能正常 |
| Shell | 75% | 基础命令可用 |
| 网络 | 55% | 默认断网符合预期 |
| Node.js | 6% | 内存限制影响 |


## 关键经验总结

### 1. 权限最小化
- 不用 `privileged`，只用必要的 capabilities
- 非 root 用户运行（UID 99999）
- 只读挂载系统目录

### 2. 资源限制分层
- 时间限制：防止死循环
- 文件大小：防止磁盘占满
- 物理内存：Cgroup V2（防止 OOM）
- CPU：Cgroup V2（防止 CPU 占满）

### 3. 网络隔离策略
- 默认断网（创建空网络命名空间）
- 按需启用（共享宿主网络）
- 配置化控制

## 总结

这 3 天的开发让我深刻体会到：**技术不是目的，解决问题才是**。每一个设计决策都是在"安全"和"可用"之间找平衡。

**Skill-First 的核心价值**：
- 让 Agent 更安全（沙箱隔离）
- 让 Agent 更可靠（预定义工具）
- 让 Agent 更易维护（Skills 可测试、可复用）

---

**项目地址**：https://github.com/ssttkkl/astrbot-plugin-nsjail

