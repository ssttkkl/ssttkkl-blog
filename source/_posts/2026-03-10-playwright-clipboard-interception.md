---
title: Playwright 剪贴板拦截：自动化获取网页复制内容
date: 2026-03-10 02:52:00
categories: 技术
tags: [Playwright, 自动化, JavaScript, 爬虫]
---

## 问题背景

在自动化淘宝返利链接获取时，遇到一个技术难题：网页通过 JavaScript 调用 `navigator.clipboard.writeText()` 将内容写入剪贴板，如何在 Playwright 中拦截这个操作并获取内容？

## 核心技术：addInitScript

关键是使用 `page.addInitScript()` 在页面加载前注入代码，覆盖原生的剪贴板 API。

## 实现代码

```javascript
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: false });
  const page = await browser.newPage();
  
  let clipboardContent = null;
  
  // 在页面加载前安装剪贴板拦截器
  await page.addInitScript(() => {
    // 保存原始方法
    const originalWriteText = navigator.clipboard.writeText;
    
    // 覆盖 writeText 方法
    navigator.clipboard.writeText = function(text) {
      // 将内容存储到 window 对象
      window.__clipboardData = text;
      // 调用原始方法（可选）
      return originalWriteText.call(navigator.clipboard, text);
    };
  });
  
  // 访问目标页面
  await page.goto('https://tb.dishu.de/');
  
  // 输入关键词并点击查询
  await page.fill('input[type="text"]', '商品关键词');
  await page.click('button:has-text("查询")');
  
  // 等待并获取剪贴板内容
  await page.waitForTimeout(2000);
  clipboardContent = await page.evaluate(() => window.__clipboardData);
  
  console.log('获取到淘口令:', clipboardContent);
  
  await browser.close();
})();
```

## 关键点解析

### 1. 为什么用 addInitScript？

`addInitScript()` 在页面 JavaScript 执行前运行，可以在网页代码加载前覆盖原生 API。

**时序对比**：
```
❌ 错误：page.evaluate() → 页面已加载 → 覆盖失败
✅ 正确：addInitScript() → 覆盖 API → 页面加载
```

### 2. 数据传递

通过 `window` 对象在注入脚本和主脚本间传递数据：

```javascript
// 注入脚本中
window.__clipboardData = text;

// 主脚本中
const data = await page.evaluate(() => window.__clipboardData);
```

### 3. 保持原始功能（可选）

如果需要保持剪贴板原有功能：

```javascript
const originalWriteText = navigator.clipboard.writeText;
navigator.clipboard.writeText = function(text) {
  window.__clipboardData = text;
  return originalWriteText.call(navigator.clipboard, text);
};
```

## 完整示例：淘宝返利自动化

```javascript
const { chromium } = require('playwright');

async function getTaobaoRebate(keyword) {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  
  // 安装剪贴板拦截器
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

// 使用
getTaobaoRebate('阿达帕林凝胶').then(console.log);
```

## 其他应用场景

这个技术可以用于：
- 拦截网页复制操作
- 监控剪贴板写入
- 自动化测试中验证复制功能
- 绕过某些反爬机制

## 注意事项

1. **权限问题**：某些网站可能检测 API 覆盖
2. **异步处理**：`writeText` 返回 Promise，需要正确处理
3. **浏览器兼容性**：主要针对 Chromium 内核

## 总结

- 使用 `addInitScript()` 在页面加载前注入代码
- 覆盖 `navigator.clipboard.writeText` 拦截剪贴板操作
- 通过 `window` 对象传递数据
- 适用于需要获取网页动态生成内容的自动化场景
