---
title: 构建 Skill-First Agent：基于 AstrBot 和 NsJail 的安全沙箱实践
date: 2026-03-13 22:17:00
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

这篇文章记录了我为 AstrBot 构建 NsJail 沙箱插件的完整过程，包含所有踩过的坑和优化方案。

## 为什么需要沙箱？

AstrBot 是一个多平台聊天机器人框架，支持 QQ、Telegram、Discord 等。它的核心特性是 **LLM Function Calling**：让 AI 调用工具完成任务。

但问题来了：
- 用户可能在群聊中让 AI 执行任意命令
- 恶意用户可能尝试攻击服务器
- 不同用户的会话需要隔离

我的选择：**NsJail** - Google 开源的轻量级沙箱工具
- 基于 Linux Namespace 和 Cgroup
- 毫秒级启动
- 细粒度资源控制

## 初始方案与设计目标

**核心需求**：
- 所有会话共享运行时（Python、Node.js、curl 等）
- 每个会话有独立的 workspace（可读写，会话结束后销毁）
- 会话之间完全隔离，互不可见
- 单个会话开销 <50MB

**最初的简单实现**：

```yaml
# docker-compose.yml
services:
  astrbot:
    privileged: true  # 简单粗暴
```

```python
# 最简 NsJail 配置
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
    "--",
    "/bin/bash", "-c", command
]
```

能工作，但问题明显：
- `privileged: true` 安全风险大
- 1.2GB 的 chroot 环境
- 没有网络隔离
- 资源限制不够精准

## 踩坑记录

### 坑 1：用户命名空间冲突

**问题**：尝试启用用户命名空间实现更好的隔离：

```python
"--uid_mapping", "0:100000:1",
"--gid_mapping", "0:100000:1",
```

**报错**：
```
newgidmap: write to /proc/self/gid_map failed: Operation not permitted
```

**原因**：
- Docker 的 `privileged` 模式与用户命名空间不兼容
- 容器内 `newgidmap`/`newuidmap` 需要特殊权限
- 即使配置 `/etc/subuid` 和 `/etc/subgid` 也无效

**解决方案**：
```python
"--disable_clone_newuser",  # 禁用用户命名空间
"--user", "99999",  # 直接指定非 root 用户
"--group", "99999",
```

**工作原理**：
- NsJail 以容器内 root 身份启动
- 利用 Docker 的 `SYS_ADMIN` 权限创建挂载命名空间
- 执行完 bindmount 后通过 `setuid(99999)` 降权

**权衡**：放弃用户命名空间，换取更简单的部署。


### 坑 2：挂载命名空间失效

**问题**：为了绕过用户命名空间问题，尝试禁用挂载命名空间：

```python
"--disable_clone_newuser",
"--disable_clone_newns",  # ❌ 这导致灾难
```

**现象**：`/workspace` 变成只读，无法写入文件。

**根本原因**：
- `--disable_clone_newns` 禁用了挂载命名空间（Mount Namespace）
- 在 Linux 中，任何隔离挂载（包括 bind mount）都**必须**在独立的 Mount Namespace 中进行
- 禁用后，`--bindmount` 参数完全不生效
- `/workspace` 成为 chroot 环境中的静态只读目录

**解决方案**：
```python
"--disable_clone_newuser",  # ✅ 禁用用户命名空间
# ✅ 不要禁用挂载命名空间！
```

**为什么需要 privileged 模式？**

这就是为什么最开始要用 `privileged: true`：
- NsJail 需要创建挂载命名空间并执行 bindmount
- 这需要 `SYS_ADMIN` 权限
- 单独添加 `cap_add: [SYS_ADMIN]` 不够，还会遇到其他权限问题
- `privileged` 是最简单的解决方案

**后续优化**：
```yaml
cap_add:
  - SYS_ADMIN
security_opt:
  - apparmor=unconfined
  - seccomp=unconfined
```

### 坑 3：Session ID 特殊字符

**问题**：QQ 消息 ID 包含特殊字符（冒号、感叹号），导致目录创建失败。

**解决方案**：
```python
clean_session_id = re.sub(r'[^a-zA-Z0-9_-]', '_', session_id)
sandbox_dir = tempfile.mkdtemp(prefix=f'nsjail_{clean_session_id}_')
```

### 坑 4：Chroot 环境太重

**问题**：
- 预构建的 `/nsjail-root` 占用 1.2GB
- 需要维护两套软件（宿主 + chroot）
- 更新麻烦

**转折点**：既然已经在 Docker 容器内，为什么还要 chroot？

**优化方案**：去掉 chroot，改用纯 Bindmount

```python
nsjail_cmd = [
    "nsjail",
    "--mode", "o",
    "--user", "99999",
    "--group", "99999",
    "--disable_clone_newuser",
    # 直接挂载宿主目录
    "--bindmount", f"{sandbox_dir}:/workspace:rw",
    "--bindmount", "/usr:/usr:ro",
    "--bindmount", "/lib:/lib:ro",
    "--bindmount", "/bin:/bin:ro",
]
```

**优势**：
- 零额外空间占用
- 自动同步宿主软件更新
- 架构更简单

### 坑 5：网络隔离的反直觉逻辑

**问题**：最开始用了 `--disable_clone_newnet`，以为这样能"禁用网络"。

**实际效果**：共享宿主网络，完全没有隔离！

**真相**：NsJail 的网络隔离逻辑是反直觉的：
- **默认行为**：创建新的网络命名空间（只有 `lo` 回环，无法访问外网）
- **`--disable_clone_newnet`**：禁用网络隔离，共享宿主机网络栈

**正确方案**：
```python
# 默认：不加任何网络参数 = 完全断网

# 需要联网时：
if enable_network:
    nsjail_cmd.append("--disable_clone_newnet")
    # 关键：还需要挂载 DNS 配置
    nsjail_cmd.extend(["--bindmount", "/etc/resolv.conf:/etc/resolv.conf:ro"])
```

**为什么需要挂载 `/etc/resolv.conf`？**

即使共享了网络命名空间，由于使用 bindmount 方式挂载系统目录，沙箱内没有 DNS 配置文件：
- `ping 8.8.8.8` ✅ 可以（直接用 IP）
- `curl https://baidu.com` ❌ 失败（无法解析域名）


### 坑 6：Node.js 启动即崩溃

**问题**：使用 `--rlimit_as 512` 限制内存后，Node.js 直接崩溃：

```
退出码: 133
#
# Fatal process out of memory: SegmentedTable::InitializeTable
#
----- Native stack trace -----
1: 0x951601 [node]
2: 0x26a95e5 v8::base::FatalOOM(v8::base::OOMType, char const*) [node]
```

**深度原因分析**：

`--rlimit_as` 限制的是**虚拟内存地址空间**（Virtual Memory），而不是物理内存（RAM）。

V8 引擎的特殊行为：
- 启动时会预留几 GB 的虚拟内存地址空间（用于指针压缩、JIT、GC）
- 这些地址空间大部分是"占位"，实际物理内存占用可能只有几十 MB
- 当 `rlimit_as` 设为 512MB 时，V8 申请虚拟地址空间失败，直接触发 `FatalOOM`

**错误的限制方式**：
```python
"--rlimit_as", "512",  # ❌ 限制虚拟内存，导致 V8 崩溃
```

**正确解决方案：Cgroup V2**

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
      - SYS_ADMIN
    volumes:
      - /sys/fs/cgroup:/sys/fs/cgroup:rw  # 必须：Cgroup V2 需要读写权限
    cgroupns: host  # 使用宿主机的 cgroup 命名空间
    security_opt:
      - apparmor=unconfined
      - seccomp=unconfined
```

### 坑 7：CPU 限制的正确姿势

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


### 坑 8：Python 虚拟环境问题

#### 问题 8.1：uv 无法检测 glibc

在沙箱中使用 `uv run` 时遇到报错：

```bash
error: Failed to detect the system glibc version or musl version
```

**原因**：`uv` 底层（Rust 实现）需要读取系统信息，依赖：
- `/proc` 伪文件系统
- `/dev/null`、`/dev/urandom` 等设备节点

**解决方案**：

```python
nsjail_args.extend([
    "--bindmount", "/dev/null:/dev/null:rw",
    "--bindmount", "/dev/urandom:/dev/urandom:ro",
])
```

**重要发现**：NsJail 默认会挂载 `/proc`，无需手动指定！

#### 问题 8.2：ModuleNotFoundError

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

**解决方案**：强制物理复制

```bash
# 在宿主机安装依赖时
uv pip install -r requirements.txt --link-mode=copy
```

配合环境变量：
```python
"--env", "UV_CACHE_DIR=/tmp/.uv_cache",
"--env", "PYTHONPATH=/AstrBot/data/skills/xxx/.venv/lib/python3.12/site-packages",
```


## 关键技术决策与权衡

### 决策 1：禁用用户命名空间

**权衡**：
- ✅ 优势：部署简单，无需配置 subuid/subgid
- ❌ 劣势：沙箱内进程以固定 UID 运行
- 📊 结论：对于朋友圈范围的 Bot，可接受

### 决策 2：Bindmount vs Chroot

**权衡**：
- Chroot：1.2GB 独立环境，需要维护
- Bindmount：0MB 额外占用，自动同步更新
- 📊 结论：Bindmount 更优

### 决策 3：Cgroup V2 vs Rlimit

**权衡**：
- `rlimit_as`：限制虚拟内存，V8 引擎无法初始化
- Cgroup V2：限制物理内存，精准控制资源
- 📊 结论：Cgroup V2 是唯一可行方案

### 决策 4：默认断网 vs 默认联网

**权衡**：
- 默认联网：方便，但安全风险高
- 默认断网：安全，按需启用网络
- 📊 结论：默认断网，通过配置启用

## 最终配置方案

### Docker Compose

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

### NsJail 完整配置

```python
nsjail_cmd = [
    "nsjail",
    "--mode", "o",
    # 用户配置
    "--user", "99999",
    "--group", "99999",
    "--disable_clone_newuser",
    
    # 文件系统挂载
    "--bindmount", f"{sandbox_dir}:/workspace:rw",
    "--bindmount", "/usr:/usr:ro",
    "--bindmount", "/lib:/lib:ro",
    "--bindmount", "/bin:/bin:ro",
    "--bindmount", "/dev/null:/dev/null:rw",
    "--bindmount", "/dev/urandom:/dev/urandom:ro",
    
    # 资源限制
    "--cwd", "/workspace",
    "--time_limit", "30",
    "--max_cpus", "1",
    "--use_cgroupv2",
    "--cgroupv2_mount", "/sys/fs/cgroup",
    "--cgroup_mem_max", "536870912",  # 512MB
    "--cgroup_cpu_ms_per_sec", "800",  # 80% 单核
    "--rlimit_fsize", "100",  # 100MB 文件大小
    
    # 环境变量
    "--env", "PATH=/usr/local/bin:/usr/bin:/bin",
    "--env", "UV_CACHE_DIR=/tmp/.uv_cache",
    
    "--quiet",
    "--",
    "/bin/bash", "-c", command
]

# 网络控制（默认断网）
if enable_network:
    nsjail_cmd.extend([
        "--disable_clone_newnet",
        "--bindmount", "/etc/resolv.conf:/etc/resolv.conf:ro",
    ])
```


## Skill-First 设计理念

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

cd /AstrBot/data/skills/majsoul-query && \
  uv run majsoul-query pt-plot "玩家昵称" --games 100
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

### LLM 工具设计：图片和文件发送

**问题**：沙箱路径 vs 真实路径

- 沙箱内：`/workspace/chart.png`
- 真实路径：`/tmp/nsjail_session123_abc/chart.png`

**解决方案**：路径转换工具

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

**关键设计**：使用 `yield` 而非 `return`（async generator 语法要求）


## 会话隔离与清理

### 每个会话独立沙箱

```python
def create_sandbox(self, session_id: str) -> tuple[str, int]:
    timestamp = int(time.time())
    clean_id = re.sub(r'[^a-zA-Z0-9_-]', '_', session_id)
    sandbox_dir = tempfile.mkdtemp(prefix=f'nsjail_{clean_id}_{timestamp}_')
    self.sandboxes[session_id] = {
        'dir': sandbox_dir,
        'uid': 99999,
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

## 测试结果

综合测试（144个用例）：

| 类别 | 通过率 | 说明 |
|------|--------|------|
| 文件操作 | 100% (20/20) | 完美隔离 |
| 安全隔离 | 87.5% (7/8) | 需加强进程数限制 |
| Python | 81% (26/32) | 大部分功能正常 |
| Shell | 75% (35/47) | 基础命令可用 |
| 网络 | 55% (11/20) | 默认断网符合预期 |
| Node.js | 6% (1/17) | 内存限制影响 |

## 总结

这次开发让我深刻体会到：

**技术不是目的，解决问题才是**。每一个设计决策都是在"安全"和"可用"之间找平衡。

**Skill-First 的核心价值**：
- 让 Agent 更安全（沙箱隔离）
- 让 Agent 更可靠（预定义工具）
- 让 Agent 更易维护（Skills 可测试、可复用）

**关键经验**：
1. 权限最小化 - 不用 `privileged`，只用必要的 capabilities
2. 资源限制分层 - 时间、文件、内存、CPU 多维度控制
3. 网络隔离策略 - 默认断网，按需启用
4. 记录"为什么" - 技术决策的原因比结果更重要

---

**项目地址**：https://github.com/ssttkkl/astrbot-plugin-nsjail

**相关资源**：
- AstrBot: https://github.com/Soulter/AstrBot
- NsJail: https://github.com/google/nsjail

