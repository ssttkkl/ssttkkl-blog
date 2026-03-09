---
title: 开发Gemini Web技能：免费调用Imagen 3生图
date: 2026-03-10 03:24:00
categories: 技术
tags: [OpenClaw, AI, Gemini, 图像生成, Skill开发]
---

## 需求

想让 AI Agent 能生图，但官方 API 要么收费要么有限制。Gemini Web 版可以免费用 Imagen 3，能不能直接调用？

## 方案

逆向 Gemini Web，开发 `gemini-web` 技能，让 OpenClaw 直接调用。

## 实现

### Cookie 获取

从 Chrome 读取登录 Cookie：

```python
import browser_cookie3

def get_gemini_cookies():
    cookies = browser_cookie3.chrome(domain_name='gemini.google.com')
    return {c.name: c.value for c in cookies}
```

### 会话管理

模拟浏览器行为：

```python
class GeminiWebClient:
    def __init__(self):
        self.session = requests.Session()
        self.cookies = get_gemini_cookies()
    
    def generate_image(self, prompt):
        response = self.session.post(
            'https://gemini.google.com/api/generate',
            json={'prompt': prompt, 'model': 'imagen-3'},
            cookies=self.cookies
        )
        return response.json()['image_url']
```

### 图片下载

```python
def download_image(url, output_path):
    response = requests.get(url)
    with open(output_path, 'wb') as f:
        f.write(response.content)
```

## 使用

### 安装

```bash
npx clawhub@latest install gemini-web
```

### 命令行

```bash
uv run python scripts/generate.py "一只橘猫"
```

### OpenClaw 集成

直接说：

```
"生成图片：赛博朋克城市夜景"
```

Agent 自动调用技能生成。

## 关键点

### Cookie 刷新

Cookie 会过期：

```python
def refresh_cookies():
    new_cookies = get_gemini_cookies()
    if new_cookies:
        self.cookies = new_cookies
        return True
    return False
```

### 错误处理

```python
def generate_with_retry(prompt, max_retries=3):
    for i in range(max_retries):
        try:
            return generate_image(prompt)
        except CookieExpiredError:
            if not refresh_cookies():
                raise
        except RateLimitError:
            time.sleep(60)
    raise Exception("生成失败")
```

## 效果

- 速度：约 60 秒/张
- 质量：Imagen 3 水平
- 成本：免费

## 注意

1. 需要先在 Chrome 登录 Gemini
2. Cookie 定期过期
3. 频繁调用可能被限制
4. 仅供学习使用

## 总结

逆向 Gemini Web 实现免费生图。配合 OpenClaw，直接对话就能生成图片。

## 参考

- [gemini-web skill](https://clawhub.com/skills/gemini-web)
- Gemini: https://gemini.google.com/
