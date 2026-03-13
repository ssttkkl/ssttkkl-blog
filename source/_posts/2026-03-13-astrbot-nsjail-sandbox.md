---
title: 构建 Skill-First Agent：基于 AstrBot 和 NsJail 的实践
date: 2026-03-13 22:10:00
tags:
  - AI Agent
  - NsJail
  - Docker
  - 沙箱
  - Python
categories:
  - 技术
---

# 构建 Skill-First Agent：基于 AstrBot 和 NsJail 的实践

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

传统方案：
1. **白名单命令** - 太受限，无法应对复杂需求
2. **Docker 容器** - 太重，每个会话一个容器不现实
3. **虚拟机** - 更重，启动慢

我的选择：**NsJail** - Google 开源的轻量级沙箱工具
- 基于 Linux Namespace 和 Cgroup
- 毫秒级启动
- 细粒度资源控制
- 每个会话独立沙箱


## 第一天：架构设计与权限斗争

### 初始方案：轻量级 Chroot + Bindmount

Gemini 对话中，我们讨论了一个核心问题：**如何在 <50MB 的开销下实现会话隔离？**

**设计目标**：
- 所有会话共享运行时（Python、Node.js、curl 等）
- 每个会话有独立的 workspace（可读写，会话结束后销毁）
- 会话之间完全隔离，互不可见

**最初的 Chroot 方案**：

```python
nsjail_cmd = [
    "nsjail",
    "--mode", "o",
    "--chroot", "/nsjail-root",  # 预构建的 Alpine rootfs
    "--user", "99999",
    "--group", "99999",
    "--bindmount", f"{sandbox_dir}:/workspace:rw",
    # 挂载共享运行时
    "--bindmount", "/nsjail-root/usr/bin/python3:/usr/bin/python3:ro",
    "--bindmount", "/nsjail-root/usr/local/lib/python3.11:/usr/local/lib/python3.11:ro",
    # ...
]
```

**问题**：
1. 需要维护 1.2GB 的 `/nsjail-root` 目录
2. 挂载路径冗余（chroot 后 `/nsjail-root` 已经是根目录）
3. 软件更新需要同步两套环境

### 转折点：去 Chroot，纯 Bindmount

**关键发现**：既然已经在 Docker 容器内，为什么还要 chroot？

**简化后的方案**：

```python
nsjail_cmd = [
    "nsjail",
    "--mode", "o",
    "--user", "99999",
    "--group", "99999",
    # 只挂载必要的目录
    "--bindmount", f"{sandbox_dir}:/workspace:rw",
    "--bindmount", "/usr:/usr:ro",
    "--bindmount", "/lib:/lib:ro",
    "--bindmount", "/bin:/bin:ro",
]
```

**优势**：
- 零额外空间占用
- 自动同步宿主软件更新
- 架构更简单，维护更容易

### 初始方案：Privileged 模式

最开始，我用了最简单的方案：

```yaml
services:
  astrbot:
    privileged: true  # 给容器所有权限
```

这能工作，但问题很明显：
- 安全风险太大
- 违背了最小权限原则
- 生产环境不可接受

### 第一次优化：精细化权限

Docker 的 `privileged` 模式本质上是给容器所有 Linux Capabilities。我需要的只是其中几个：

```yaml
cap_add:
  - SYS_ADMIN  # 挂载命名空间
  - NET_ADMIN  # 网络命名空间
security_opt:
  - apparmor=unconfined
  - seccomp=unconfined
```

**为什么需要这些权限？**
- `SYS_ADMIN`：NsJail 需要创建挂载命名空间（mount namespace）
- `NET_ADMIN`：网络隔离需要操作网络命名空间
- `apparmor/seccomp`：解除对某些系统调用的限制

### 遇到的第一个坑：用户命名空间冲突

```bash
[E][2026-03-11T12:15:23+0000] clone(flags=CLONE_NEWUSER) failed: Operation not permitted
```

**原因**：Docker 的 `privileged` 模式与用户命名空间不兼容。

在 Docker 容器内创建用户命名空间时，需要 `newgidmap` 和 `newuidmap` 工具来映射 UID/GID。但这些工具需要特殊的文件权限（setuid bit），在某些 Docker 配置下会失败。

**解决方案**：
```python
"--disable_clone_newuser",  # 禁用用户命名空间
"--user", "99999",  # 直接指定非 root 用户
"--group", "99999",
```

**工作原理**：
- NsJail 以容器内的 root 身份启动
- 利用 Docker 的 `SYS_ADMIN` 权限创建挂载命名空间
- 执行 bindmount 操作
- 最后通过 `setuid(99999)` 降权到非 root 用户

这是第一个重要的权衡：**放弃用户命名空间，换取更简单的部署**。

### 第二个坑：挂载命名空间失效

在尝试禁用用户命名空间后，又遇到新问题：

```python
"--disable_clone_newuser",
"--disable_clone_newns",  # ❌ 这导致 bindmount 完全失效
```

**现象**：`/workspace` 变成只读，无法写入文件。

**根本原因**：

`--disable_clone_newns` 禁用了挂载命名空间（Mount Namespace）。在 Linux 中，任何形式的隔离挂载（包括 bind mount）都**必须**在独立的 Mount Namespace 中进行。

禁用后：
- `--bindmount` 参数完全不生效
- 沙箱只能看到静态的目录结构
- `/workspace` 成为只读目录

**正确方案**：

```python
"--disable_clone_newuser",  # ✅ 禁用用户命名空间（避免 newgidmap 错误）
# ✅ 不要禁用挂载命名空间！让 bindmount 正常工作
```


## 第二天：架构选择 - Chroot vs Bindmount

### 最初的想法：Chroot 环境

在 Gemini 对话中，我最开始设计的是基于 Alpine Linux 的完整 chroot 方案：

```dockerfile
FROM alpine:latest
ENV PLAYWRIGHT_BROWSERS_PATH=/usr/local/share/playwright-browsers

RUN apk add --no-cache \
    bash python3 py3-pip nodejs npm curl \
    chromium font-noto-cjk nss freetype harfbuzz \
    && pip3 install --no-cache-dir playwright requests --break-system-packages \
    && playwright install chromium \
    && chmod -R 755 $PLAYWRIGHT_BROWSERS_PATH

RUN adduser -D -u 99999 sandbox
WORKDIR /workspace
```

然后导出为 rootfs：
```bash
docker build -f Dockerfile.nsjail-root -t nsjail-rootfs .
docker create --name temp nsjail-rootfs
docker export temp | tar -C /opt/astrbot/nsjail-root -xf -
docker rm temp
```

**问题**：
1. 占用 1.2GB 磁盘空间
2. 需要维护两套软件（宿主 + chroot）
3. 更新麻烦
4. Playwright 浏览器权限问题（root 安装，99999 用户无法访问）

### 转折点：Bindmount 方案

在实现过程中突然意识到：**既然已经在 Docker 容器内，为什么不直接复用容器的软件？**

这是一个关键的架构简化：

```python
nsjail_cmd = [
    "nsjail",
    "--mode", "o",
    "--user", "99999",
    "--group", "99999",
    "--disable_clone_newuser",
    # 不需要 chroot，直接 bindmount 系统目录
    "--bindmount", "/usr:/usr:ro",
    "--bindmount", "/lib:/lib:ro",
    "--bindmount", "/bin:/bin:ro",
    "--bindmount", f"{sandbox_dir}:/workspace:rw",
]
```

**优势**：
- 零额外空间占用（移除了 1.2GB 的 chroot 环境）
- 自动同步宿主软件更新
- 架构更简单，维护成本更低
- 避免了 Playwright 权限问题

**关键发现**：沙箱内可以直接使用宿主的 Python、Node.js、Git 等所有软件！

### 重要的坑：冗余挂载

在 Gemini 对话中，AI 最初建议了这样的配置：

```python
# ❌ 错误示例
"--chroot", "/nsjail-root",
"--bindmount", "/nsjail-root/usr/bin/python3:/usr/bin/python3:ro",
"--bindmount", "/nsjail-root/usr/lib/python3.11:/usr/lib/python3.11:ro",
```

**问题**：当使用 `--chroot` 后，`/nsjail-root` 已经变成了根目录 `/`，里面的程序自然就在 `/usr/bin/` 下了。这些 bindmount 不仅冗余，还可能引发挂载嵌套报错。

**正确做法**：要么用 chroot（不需要额外 bindmount），要么用 bindmount（不需要 chroot）。我选择了后者。

### 第二个坑：网络隔离的反直觉逻辑

最开始我用了 `--disable_clone_newnet`，以为这样就能"禁用网络"。

**实际效果**：共享宿主网络，完全没有隔离！

**真相**：NsJail 的网络隔离逻辑是反直觉的：
- **默认行为**：创建新的网络命名空间（只有 `lo` 回环，无法访问外网）
- **`--disable_clone_newnet`**：禁用网络隔离，共享宿主机网络栈

这导致了一个经典错误：

```bash
# ❌ 错误：以为这样能断网
--disable_clone_newnet

# ✅ 正确：什么都不加就是断网
# （默认创建空的网络命名空间）
```

**正确方案**：
```python
# 默认：不加任何网络参数 = 完全断网
nsjail_args = [...]

# 需要联网时：才禁用网络隔离
if enable_network:
    nsjail_args.append("--disable_clone_newnet")
    # 关键：还需要挂载 DNS 配置
    nsjail_args.extend(["--bindmount", "/etc/resolv.conf:/etc/resolv.conf:ro"])
```

**为什么需要挂载 `/etc/resolv.conf`？**

即使共享了网络命名空间，由于使用了 bindmount 方式挂载系统目录，沙箱内没有 DNS 配置文件，会导致：
- `ping 8.8.8.8` ✅ 可以（直接用 IP）
- `curl https://baidu.com` ❌ 失败（无法解析域名）


## 第三天：资源限制与 Node.js OOM

### 第三个坑：Node.js 启动即崩溃

使用 `--rlimit_as 512` 限制内存后，Node.js 直接崩溃：

```
退出码: 133
输出:
#
# Fatal process out of memory: SegmentedTable::InitializeTable (subspace allocation)
#
----- Native stack trace -----
1: 0x951601 [node]
2: 0x26a95e5 v8::base::FatalOOM(v8::base::OOMType, char const*) [node]
```

**深度原因分析**：

`--rlimit_as` 限制的是**虚拟内存地址空间**（Virtual Memory Address Space），而不是物理内存（RAM）。

V8 引擎的特殊行为：
- 启动时会预留几 GB 的虚拟内存地址空间（用于指针压缩、JIT 编译、垃圾回收）
- 这些地址空间大部分是"占位"，实际物理内存占用可能只有几十 MB
- 当 `rlimit_as` 设为 512MB 时，V8 申请虚拟地址空间失败，直接触发 `FatalOOM`

**错误的限制方式**：
```python
"--rlimit_as", "512",  # ❌ 限制虚拟内存，导致 V8 崩溃
```

**正确的解决方案：Cgroup V2**

```python
# ✅ 删除 rlimit_as，改用 Cgroup V2 限制物理内存
"--use_cgroupv2",
"--cgroupv2_mount", "/sys/fs/cgroup",
"--cgroup_mem_max", "536870912",  # 512MB 物理内存
```

**关键区别**：
- `rlimit_as`：限制虚拟内存（地址空间），V8 引擎无法初始化
- `cgroup_mem_max`：限制物理内存（RSS），只在真正消耗内存时触发限制

**Docker 配置要求**：

```yaml
services:
  astrbot:
    cap_add:
      - SYS_ADMIN  # 必须：用于挂载命名空间
    volumes:
      - /sys/fs/cgroup:/sys/fs/cgroup:rw  # 必须：Cgroup V2 需要读写权限
    cgroupns: host  # 使用宿主机的 cgroup 命名空间
```

### CPU 限制的正确姿势

Cgroup V2 还支持 CPU 时间配额限制：

```python
"--cgroup_cpu_ms_per_sec", "1000",  # 限制为 1 个 CPU 核心（100%）
```

**计算公式**：
- 1000ms = 1 个完整 CPU 核心（100%）
- 2000ms = 2 个 CPU 核心（200%）
- 500ms = 半个 CPU 核心（50%）

**与 `--max_cpus` 的联动**：

```python
"--max_cpus", "1",  # 物理层面：只能调度到 1 个核心
"--cgroup_cpu_ms_per_sec", "800",  # 时间层面：最多用 80% 算力
```

这两个参数是**取交集**的：
- 如果 `max_cpus=1` + `cpu_ms_per_sec=2000`：实际只能用到 100%（1 核的上限）
- 如果 `max_cpus=4` + `cpu_ms_per_sec=2000`：可以用 4 核，但总算力不超过 200%

**推荐配置**：
```python
# 方案 A：严格单核限制（推荐）
"--max_cpus", "1",
"--cgroup_cpu_ms_per_sec", "800",  # 单核 80%

# 方案 B：多核温和限制
"--max_cpus", "2",
"--cgroup_cpu_ms_per_sec", "1000",  # 2 核总共 100%
```


## 关键技术决策与权衡

### 决策 1：禁用用户命名空间

**背景**：Docker + NsJail 的双层命名空间嵌套导致 `newgidmap` 权限错误。

**权衡**：
- ✅ 优势：部署简单，无需配置 subuid/subgid
- ❌ 劣势：沙箱内进程以固定 UID 运行，无法实现多用户隔离
- 📊 结论：对于朋友圈范围的 Bot，可接受

### 决策 2：Bindmount vs Chroot

**背景**：需要共享运行时，减少磁盘占用。

**权衡**：
- Chroot 方案：1.2GB 独立环境，需要维护
- Bindmount 方案：0MB 额外占用，自动同步更新
- 📊 结论：Bindmount 更优，架构更简单

### 决策 3：Cgroup V2 vs Rlimit

**背景**：Node.js 因 `rlimit_as` 限制无法启动。

**权衡**：
- `rlimit_as`：限制虚拟内存，V8 引擎无法初始化
- Cgroup V2：限制物理内存，精准控制资源
- 📊 结论：Cgroup V2 是唯一可行方案

### 决策 4：默认断网 vs 默认联网

**背景**：群聊场景需要防止恶意网络请求。

**权衡**：
- 默认联网：方便，但安全风险高
- 默认断网：安全，按需启用网络
- 📊 结论：默认断网，通过配置启用

## 第四天：Python 虚拟环境的深坑

### 第四个坑：uv 无法检测 glibc

在沙箱中使用 `uv run` 时遇到报错：

```bash
error: Failed to detect the system glibc version or musl version
```

**原因**：`uv` 底层（Rust 实现）需要读取系统信息来探测架构，依赖：
- `/proc` 伪文件系统
- `/dev/null`、`/dev/urandom` 等设备节点

**解决方案**：

NsJail 默认会挂载 `/proc`，但需要确保设备节点可访问：

```python
nsjail_args.extend([
    "--bindmount", "/dev/null:/dev/null:rw",
    "--bindmount", "/dev/urandom:/dev/urandom:ro",
])
```

**重要发现**：NsJail 没有 `--mount_proc` 参数！

这是 Gemini 对话中一个经典的"AI 参数幻觉"案例。AI 把 Linux `unshare` 命令的 `--mount-proc` 参数和 NsJail 的参数体系搞混了。

**真相**：
- NsJail **默认会自动挂载 `/proc`**
- 除非显式使用 `--disable_proc`，否则 `/proc` 一直存在
- 不需要（也不能）手动指定 `--mount_proc`
- 如果在命令中加入 `--mount_proc`，NsJail 会因为无法识别参数而直接崩溃

**教训**：在使用 AI 建议时，务必验证参数是否真实存在。可以通过 `nsjail --help` 查看完整参数列表。

### 第五个坑：ModuleNotFoundError

使用 `PYTHONPATH` 方案时，找不到已安装的模块：

```python
ModuleNotFoundError: No module named 'matplotlib'
```

**深层原因**：`uv` 的全局缓存机制

`uv` 为了节省磁盘空间，使用**硬链接/符号链接**指向全局缓存：

```bash
.venv/lib/python3.12/site-packages/matplotlib -> ~/.cache/uv/...
```

当沙箱只挂载 `/AstrBot/data/skills` 时：
- 链接文件存在 ✅
- 链接目标不存在 ❌（`~/.cache/uv` 未挂载）
- Python 顺着 `PYTHONPATH` 找到"死链接"，报 `ModuleNotFoundError`

**解决方案 A：强制物理复制**

```bash
# 在宿主机安装依赖时
uv pip install -r requirements.txt --link-mode=copy
```

这样 `.venv` 内部就是真实的物理文件，不依赖外部缓存。

**解决方案 B：挂载 uv 缓存**

```python
"--bindmount", "/root/.cache/uv:/root/.cache/uv:ro",
```

但这会增加沙箱的攻击面，不推荐。

**最终方案**：使用方案 A + 环境变量

```python
"--env", "UV_CACHE_DIR=/tmp/.uv_cache",  # 避免权限问题
"--env", "PYTHONPATH=/AstrBot/data/skills/majsoul-query/.venv/lib/python3.12/site-packages",
```

### 什么是 Skill-First？

传统 Agent 设计：
```
用户请求 → LLM 思考 → 生成代码 → 执行
```

Skill-First 设计：
```
用户请求 → LLM 选择 Skill → 调用预定义工具 → 返回结果
```

**核心差异**：
- 不让 LLM 写代码，而是调用已有的 Skills
- Skills 是经过测试的、安全的、可维护的
- LLM 只负责理解意图和参数传递

### 实际案例：雀魂战绩查询

**Skill 定义**（`majsoul-query/SKILL.md`）：

```markdown
## 使用方法

cd /AstrBot/data/skills/majsoul-query && uv run majsoul-query pt-plot "玩家昵称" --games 100
```

**LLM 的工作**：
1. 识别用户想查询雀魂战绩
2. 提取玩家昵称
3. 调用 `execute_shell` 工具
4. 调用 `send_sandbox_image` 发送生成的图片

**用户体验**：
```
用户：查一下无铭金重的PT走势
Bot：[生成并发送PT走势图]
```


## LLM 工具设计：图片和文件发送

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
1. LLM 在沙箱中生成图片：`/workspace/chart.png`
2. LLM 调用 `send_sandbox_image("/workspace/chart.png")`
3. 插件转换为真实路径并发送

### 关键设计：Async Generator

注意 `yield` 而非 `return`：

```python
# ❌ 错误：async generator 不能 return 值
return "已发送图片"

# ✅ 正确：只 yield 结果
yield event.image_result(real_path)
```

这是我遇到的第四个坑：Python 的 async generator 语法限制。


## 会话隔离与清理

### 每个会话独立沙箱

```python
def create_sandbox(self, session_id: str) -> tuple[str, int]:
    timestamp = int(time.time())
    sandbox_dir = tempfile.mkdtemp(prefix=f'nsjail_{session_id}_{timestamp}_')
    self.sandboxes[session_id] = {
        'dir': sandbox_dir,
        'uid': uid,
        'created_at': timestamp
    }
```

**设计要点**：
- 每个 QQ 用户/群聊有独立沙箱
- 同一会话内多次命令共享沙箱（支持多步骤操作）
- 会话结束后自动销毁

### 定时清理机制

```python
async def _cleanup_loop(self):
    while True:
        await asyncio.sleep(600)  # 10分钟
        self.sandbox_mgr.cleanup_old_sandboxes()  # 清理超过3天的沙箱
```

**为什么需要清理？**
- 异常退出可能导致沙箱未销毁
- 长时间运行会积累大量临时目录
- 定期清理保证磁盘空间


## 测试结果与经验总结

### 综合测试（144个用例）

| 类别 | 通过率 | 说明 |
|------|--------|------|
| 文件操作 | 100% (20/20) | 完美隔离 |
| 安全隔离 | 87.5% (7/8) | 需加强进程数限制 |
| Python | 81% (26/32) | 大部分功能正常 |
| Shell | 75% (35/47) | 基础命令可用 |
| 网络 | 55% (11/20) | 默认断网符合预期 |
| Node.js | 6% (1/17) | 内存限制影响 |

### 关键经验

**1. 权限最小化**
- 不用 `privileged`，只用必要的 capabilities
- 非 root 用户运行（UID 99999）
- 只读挂载系统目录

**2. 资源限制分层**
- 时间限制：`--time_limit`（防止死循环）
- 文件大小：`--rlimit_fsize`（防止磁盘占满）
- 物理内存：Cgroup V2（防止 OOM）
- CPU：Cgroup V2（防止 CPU 占满）

**3. 网络隔离策略**
- 默认断网（创建空网络命名空间）
- 按需启用（共享宿主网络）
- 配置化控制


## 私聊与群聊的差异化设计

### 场景差异

**私聊场景**：
- 用户与 Bot 一对一交互
- 信任度高，可以执行更多操作
- 会话连续性强

**群聊场景**：
- 多用户共享一个 Bot
- 需要更严格的权限控制
- 防止恶意用户攻击

### 设计策略

**会话隔离**：
```python
session_id = event.session_id or "default"
# QQ 私聊：session_id = user_id
# QQ 群聊：session_id = group_id
```

每个会话独立沙箱，互不影响。

**权限分级**（未来可扩展）：
- 私聊：允许网络访问、更大内存限制
- 群聊：默认断网、严格资源限制
- 管理员：可以调整配置


## 未来展望

### 待改进的问题

1. **Node.js 支持**：当前内存限制导致大部分 Node.js 程序无法运行
2. **进程数限制**：需要添加 `--rlimit_nproc` 防止 Fork 炸弹
3. **中文字体**：matplotlib 生成的图表中文显示为方框（正在修复）

### 可能的扩展

1. **Skill 市场**：用户可以分享和安装 Skills
2. **权限分级**：根据用户身份动态调整沙箱配置
3. **多语言支持**：Python、Node.js、Go、Rust...
4. **GPU 支持**：为 AI 模型推理提供 GPU 访问

## 从 Gemini 对话中学到的经验

在整个开发过程中，我与 Gemini 进行了深度的技术对话。这个过程本身就是一次有趣的"人机协作"实验。

### AI 辅助开发的价值

**快速方案验证**：
- Gemini 能快速给出多种技术方案
- 帮助理解复杂的 Linux 底层机制（Namespace、Cgroup）
- 提供详细的参数说明和配置示例

**但也要警惕 AI 的"幻觉"**：

最典型的案例是 `--mount_proc` 参数：
- Gemini 建议使用 `--mount_proc` 挂载 `/proc`
- 实际上 NsJail 根本没有这个参数
- AI 把 `unshare` 命令的参数和 NsJail 搞混了

**教训**：
1. **验证 AI 建议**：通过 `--help` 或官方文档确认参数
2. **理解原理**：不要盲目复制代码，要理解为什么这样做
3. **快速试错**：AI 给的方案不一定对，但能快速缩小搜索空间

### 反直觉的设计

NsJail 的网络隔离逻辑是最反直觉的：
- 想断网 → 什么都不加（默认创建空网络命名空间）
- 想联网 → 加 `--disable_clone_newnet`（禁用网络隔离）

这种"双重否定"的设计让人困惑，但理解后就很清晰了。

### 虚拟内存 vs 物理内存

`rlimit_as` 导致 Node.js 崩溃是最难调试的问题：
- 表面现象：Node.js 启动即 OOM
- 深层原因：V8 引擎需要大量虚拟内存地址空间
- 解决方案：用 Cgroup V2 限制物理内存

**关键认知**：虚拟内存 ≠ 物理内存。现代程序（尤其是 JIT 引擎）会预留大量虚拟地址空间，但实际物理内存占用很小。

### 架构简化的力量

从 1.2GB 的 chroot 环境到零额外空间的 bindmount 方案，这是最大的架构优化：
- 删除了整个 chroot 构建流程
- 直接复用宿主软件
- 维护成本大幅降低

**启示**：有时候"做减法"比"做加法"更有价值。

## 总结

这 3 天的开发让我深刻体会到：

**技术不是目的，解决问题才是**。每一个设计决策都是在"安全"和"可用"之间找平衡。

**Skill-First 的核心价值**：
- 让 Agent 更安全（沙箱隔离）
- 让 Agent 更可靠（预定义工具）
- 让 Agent 更易维护（Skills 可测试、可复用）

如果你也在构建 Agent 系统，希望这篇文章能给你一些启发。

---

**项目地址**：https://github.com/ssttkkl/astrbot-plugin-nsjail

**相关资源**：
- AstrBot: https://github.com/Soulter/AstrBot
- NsJail: https://github.com/google/nsjail


## 最终配置方案

经过 4 天的迭代，这是最终稳定的配置：

### Docker Compose 配置

```yaml
services:
  astrbot:
    image: ghcr.io/ssttkkl/astrbot-plugin-nsjail:main
    ports:
      - "6185:6185"
    volumes:
      - ${PWD}/data:/AstrBot/data
      - /sys/fs/cgroup:/sys/fs/cgroup:rw
    cap_add:
      - SYS_ADMIN
    security_opt:
      - apparmor=unconfined
      - seccomp=unconfined
    cgroupns: host
```

### NsJail 核心参数

```python
nsjail_cmd = [
    "nsjail",
    "--mode", "o",
    "--user", "99999",
    "--group", "99999",
    "--disable_clone_newuser",
    
    # 文件系统
    "--bindmount", f"{sandbox_dir}:/workspace:rw",
    "--bindmount", "/usr:/usr:ro",
    "--bindmount", "/lib:/lib:ro",
    "--bindmount", "/bin:/bin:ro",
    
    # 设备节点
    "--bindmount", "/dev/null:/dev/null:rw",
    "--bindmount", "/dev/urandom:/dev/urandom:ro",
    
    # 资源限制
    "--cwd", "/workspace",
    "--time_limit", "30",
    "--use_cgroupv2",
    "--cgroup_mem_max", "536870912",
    "--cgroup_cpu_ms_per_sec", "800",
    
    "--quiet",
    "--",
    "/bin/bash", "-c", command
]
```

### 网络控制

```python
# 默认：完全断网（不添加任何网络参数）

# 需要网络时：
if enable_network:
    nsjail_cmd.extend([
        "--disable_clone_newnet",
        "--bindmount", "/etc/resolv.conf:/etc/resolv.conf:ro",
    ])
```

