---
title: OpenClaw 技能文档的可执行性改进：从人类友好到 AI 可执行
date: 2026-03-16
tags: [OpenClaw, AI Agent, 技能开发, 文档工程]
categories: [AI 开发]
---

## 起因

最近在开发 OpenClaw 技能的时候，遇到个挺有意思的问题：文档写得挺详细的，我自己看着很清楚，但 AI Agent 执行起来老是出错，或者得反复确认好几次才能搞定。

这让我开始琢磨：**技能文档到底应该写给谁看？**

<!-- more -->

## 问题是怎么发现的

3月13号到16号这几天，我把工作空间里的 22 个技能挨个审查了一遍。审查过程中发现了个普遍存在的问题：

**文档对人类很友好，但对 AI 来说不够"可执行"。**

什么意思呢？举两个例子你就明白了。

### 案例 1：gemini-web 技能

**原来的文档是这么写的：**
```markdown
使用 `message` 工具将图片发送到 channel
```

看起来没问题对吧？但 AI 看到这句话会懵：
- 具体参数是啥？
- channel_id 从哪拿？
- 图片路径在哪？

**后来改成这样：**
```markdown
## 发送图片到频道

1. 获取最新生成的图片路径：
   ```bash
   ls -t ~/Library/Application\ Support/gemini-web/images/*.png | head -1
   ```

2. 发送图片（调用 `message` 工具）：
   - action: "send"
   - target: <从消息元数据获取 group_id 或 user_id>
   - media: <图片路径>
   - caption: "生成的图片"
```

这下 AI 就知道该怎么做了。

### 案例 2：taobao-rebate 技能

**原来：**
```markdown
使用 exec 工具运行脚本
```

**改进后：**
```markdown
## 执行流程（AI 操作手册）

**Step 3: 执行命令**
调用 `exec` 工具：
- command: `cd ~/.openclaw/workspace/skills/taobao-rebate && python3 taobao_rebate.py "关键词"`
- 等待输出（JSON 格式）
```

看出区别了吗？改进后的版本把每个细节都说清楚了。

## 核心问题：文档要服务两类读者

后来我想明白了，技能文档其实要同时服务两类读者：

**人类用户：**
- 想知道"为什么"和"什么时候用"
- 需要概念性的解释
- 想看高层次的工作流程

**AI Agent：**
- 需要知道"怎么做"和"调用什么工具"
- 要具体的参数和命令
- 数据流要清清楚楚

问题就在这儿——大多数技能文档混在一起写，给 AI 的指令往往不够具体。

## 我总结的 4 个可执行性原则

审查完这些技能后，我总结出了 4 个原则：

### 原则 1：数据流要明确

❌ **模糊：**
```markdown
将文档传递给 AWS Code
```

✅ **清晰：**
```markdown
读取 `references/api-spec.md`，在 `sessions_spawn` 的 `task` 参数中包含文件内容
```

### 原则 2：工具调用要具体

❌ **模糊：**
```markdown
使用 exec 工具运行脚本
```

✅ **清晰：**
```markdown
调用 `exec` 工具：
- command: `cd /path && python3 script.py arg1`
- timeout: 30
```

### 原则 3：错误处理要有路径

❌ **模糊：**
```markdown
如果失败，重试
```

✅ **清晰：**
```markdown
如果返回非零退出码：
1. 检查 stderr
2. 如果包含 'Permission denied' → 检查文件权限
3. 如果包含 'Not found' → 检查路径是否正确
```

### 原则 4：上下文依赖要说清楚

❌ **模糊：**
```markdown
从上下文获取 channel_id
```

✅ **清晰：**
```markdown
从当前消息元数据获取：
- QQ 群 → `message.group_id`
- QQ 私聊 → `message.user_id`
- Discord → `message.channel_id`
- 无上下文 → 询问用户
```

## 审查成果

4 天时间，审查了 21 个技能（有 1 个格式不标准跳过了），结果还挺不错：

**评分分布：**
- ⭐⭐⭐⭐⭐ (5星)：16个 (76%)
- ⭐⭐⭐⭐ (4星)：3个 (14%)
- ⭐⭐⭐ (3星)：2个 (10%)

整体质量挺高的，76% 都达到了 5 星标准。

### 5 星技能的共同特征

分析了这些高分技能后，我发现它们都有这么几个特点：

#### 1. 意图映射表

这个真的很实用。看个例子（bilibili-cli 技能）：

| User Intent | Extract Info | Command Template | Notes |
|-------------|--------------|------------------|-------|
| "这个视频信息" + URL | BV ID from URL | `bili video {bv_id}` | Accepts full URL |
| "视频字幕" | BV ID | `bili video {bv_id} --subtitle` | Requires login |
| "B站搜索XX" | Keyword | `bili search "{keyword}" --type video` | Default searches users |

有了这个表，AI 直接查表就知道该执行什么命令，不用自己推断。

#### 2. 执行流程（5-6 步）

http-share 技能的流程写得特别清楚：

```markdown
## 执行流程（AI 操作手册）

**Step 1: 确认分享内容**
- 询问用户要分享的文件或目录路径

**Step 2: 获取局域网 IP**
调用 `exec` 工具：
- command: `ifconfig | grep "inet " | grep -v 127.0.0.1 | awk '{print $2}'`

**Step 3: 启动 HTTP 服务器**
调用 `exec` 工具：
- command: `cd /path && uv run python main.py --port 54321`
- background: true

**Step 4: 生成访问链接**
格式：`http://{ip}:{port}/file.zip`

**Step 5: 告知用户**
输出格式：
```
✅ HTTP 服务器已启动
📡 局域网访问地址：http://192.168.1.18:54321/file.zip
⚠️ 服务器在后台运行，使用完毕后记得停止
```
```

每一步都说得明明白白。

#### 3. 错误处理表格

markdown-convert 技能的错误处理表格：

| 错误信息 | 原因 | 解决方案 |
|---------|------|----------|
| `Rate limit exceeded` | 超过 500/天限制 | 等待 24 小时或使用其他方法 |
| `Invalid URL` | URL 格式错误 | 检查 URL 是否完整 |
| `Conversion failed` | 网页无法访问 | 尝试 browser 方法 |
| `File not found` | 路径错误 | 使用绝对路径 |

遇到错误直接查表，省事。

#### 4. 命令参考（完整示例）

mijia 技能的命令映射表：

```markdown
## 命令映射表

| 用户意图 | 提取信息 | 具体命令 |
|---------|---------|----------|
| "打开暖风机" | 设备名称 | `uv run mijiaapi set --did {did} --prop_name on --value true` |
| "暖风机温度设置为 22 度" | 设备名称 + 温度 | `uv run mijiaapi set --did {did} --prop_name target_temperature --value 22` |
| "查询暖风机状态" | 设备名称 | `uv run mijiaapi get --did {did}` |
```

#### 5. 验证清单

mermaid-diagrams 技能的检查清单：

```markdown
## 验证检查清单

交付前必须检查：
- [ ] 语法正确（无未闭合的括号、引号）
- [ ] 节点 ID 唯一且有效
- [ ] 箭头方向正确
- [ ] 文本标签清晰
- [ ] 可以在 mermaid.live 渲染
- [ ] 符合用户需求
```

## 几种特殊技能的最佳实践

### 转换类技能（nonebot-plugin-to-skill）

这类技能需要：
- 完整的转换工作流（9 个阶段）
- 真实项目案例（我用的是 ssttkkl/nonebot-plugin-majsoul）
- 包管理器选择规则（uv/pdm/poetry）
- 保留原始架构（async/await 不要动）

### MCP 集成技能（mirroir）

这类技能要注意：
- 通过 mcporter 调用
- 故障排查指南要详细
- 权限配置说明（比如屏幕录制权限）
- 健康检查流程

### 跨技能协作（conversation-to-blog）

这个挺有意思的——不是直接调用其他技能，而是先 `read` 其他技能的 SKILL.md，这样能保证技能间松耦合。

示例：
```markdown
## Step 4: 人性化处理（强制）

1. 读取 humanize-zh 技能文档：
   调用 `read` 工具：
   - path: `~/.openclaw/workspace/skills/humanize-zh/SKILL.md`

2. 按照 humanize-zh 的原则处理文章内容

3. 输出人性化后的 Markdown
```

## 改进前后对比

### gemini-web 技能

**改进前（3/5 星）：**
- 命令模板清晰
- 数据目录明确
- ❌ 工具调用语法不对
- ❌ 上下文依赖说不清
- ❌ 没有错误处理表格

**改进后（5/5 星）：**
- ✅ 加了意图映射表（5 种场景）
- ✅ 完整的执行流程（5 步）
- ✅ 工具调用语法正确了
- ✅ 上下文获取方式说清楚了
- ✅ 错误处理表格（4 种场景）

### mijia 技能

**改进前（4/5 星）：**
- 命令模板能直接用
- ❌ 没有触发条件
- ❌ 执行流程不完整

**改进后（5/5 星）：**
- ✅ 加了触发条件（中英文都有）
- ✅ 5 步执行流程（识别设备 → 判断操作 → 执行命令 → 处理响应 → 更新记忆）
- ✅ 命令映射表（7 种场景）
- ✅ 设备信息存储说明

## 我整理的技能文档模板

基于这次审查经验，我整理了个通用模板，以后写技能文档可以参考：

```markdown
# Skill Name

## 触发条件（Trigger Conditions）

**中文：**
- "关键词1"
- "关键词2"

**English:**
- "keyword1"
- "keyword2"

## 执行流程（AI 操作手册）

**Step 1: 解析用户意图**
- 提取关键信息：XXX
- 判断操作类型：XXX

**Step 2: 准备数据**
调用 `read` 工具：
- path: `references/xxx.md`

**Step 3: 执行命令**
调用 `exec` 工具：
- command: `xxx`
- timeout: 30

**Step 4: 处理响应**
- 如果成功 → 格式化输出
- 如果失败 → 检查错误类型

**Step 5: 交付结果**
输出格式：
```
✅ 操作成功
📊 结果：XXX
```
```

## 意图映射表

| 用户意图 | 提取信息 | 具体命令 | 备注 |
|---------|---------|----------|------|
| XXX | XXX | XXX | XXX |

## 错误处理

| 错误信息 | 原因 | 解决方案 |
|---------|------|----------|
| XXX | XXX | XXX |

## 命令参考

完整的命令列表和参数说明...
```

## 几点经验

### 1. 文档是给 AI 的操作手册

说白了，技能文档不是用户手册，而是 **AI 的操作手册**。每句话都得能转换成具体的工具调用或判断逻辑。

### 2. 示例比描述管用

一个完整的可执行示例，胜过一堆抽象的描述。代码示例得是真实能用的，别写伪代码。

### 3. 把决策逻辑做成表格

把"如果...那么..."的逻辑转成表格，AI 直接查表执行，不用自己推断。

### 4. 所有依赖都要说清楚

别假设 AI 知道怎么获取某个值。从哪拿、怎么拿、拿不到怎么办，都得说清楚。

### 5. 持续迭代

技能文档不是一次写完就完事了。用的过程中发现问题，马上更新。

## 后续计划

接下来打算做这几件事：

1. **搞个技能文档检查清单** - 自动化审查流程
2. **开发文档质量评分工具** - 把可执行性量化
3. **建个最佳实践库** - 多收集些案例

## 总结

技能文档的可执行性改进是个持续的过程。通过这次系统性审查和改进，AI Agent 执行任务确实准确多了，效率也高了不少。

**核心就一句话：让 AI 能直接执行，别让它猜。**

---

**相关资源：**
- OpenClaw 文档：https://docs.openclaw.org
- 技能开发指南：https://docs.openclaw.org/skills
- 本次审查的完整记录：`memory/2026-03-16-skill-review-round5.md`
