---
title: Playwright拦截剪贴板获取网页复制内容
date: 2026-03-10 02:52:00
categories: 技术
tags: [Playwright, 自动化, JavaScript, 爬虫]
---

## 问题

网页用 `navigator.clipboard.writeText()` 写剪贴板，怎么在 Playwright 里拦截并获取内容？

## 解决方法

用 `page.addInitScript()` 在页面加载前注入代码，覆盖剪贴板 API。

## 代码

```javascript
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: false });
  const page = await browser.newPage();
  
  // 在页面加载前拦截
  await page.addInitScript(() => {
    navigator.clipboard.writeText = function(text) {
      window.__clipboardData = text;
      return Promise.resolve();
    };
  });
  
  await page.goto('https://tb.dishu.de/');
  await page.fill('input[type="text"]', '商品关键词');
  await page.click('button:has-text("查询")');
  
  await page.waitForTimeout(2000);
  const content = await page.evaluate(() => window.__clipboardData);
  
  console.log('获取到:', content);
  await browser.close();
})();
```

## 为什么用addInitScript

`addInitScript()` 在页面 JavaScript 执行前运行，可以提前覆盖原生 API。

时序：
```
✅ addInitScript() → 覆盖API → 页面加载
❌ page.evaluate() → 页面已加载 → 覆盖失败
```

## 数据传递

通过 `window` 对象传：

```javascript
// 注入脚本
window.__clipboardData = text;

// 主脚本
const data = await page.evaluate(() => window.__clipboardData);
```

## 完整示例

```javascript
async function getTaobaoRebate(keyword) {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  
  await page.addInitScript(() => {
    navigator.clipboard.writeText = function(text) {
      window.__clipboardData = text;
      return Promise.resolve();
    };
  });
  
  await page.goto('https://tb.dishu.de/');
  await page.fill('input[placeholder="输入关键词"]', keyword);
  await page.click('button:has-text("搜索")');
  await page.waitForTimeout(2000);
  
  const result = await page.evaluate(() => window.__clipboardData);
  await browser.close();
  return result;
}
```

## 其他用途

- 拦截网页复制操作
- 监控剪贴板写入
- 自动化测试验证复制功能

## 注意

1. 有些网站可能检测 API 覆盖
2. `writeText` 返回 Promise，要正确处理
3. 主要针对 Chromium 内核
