---
title: Docker配置HTTP代理加速镜像拉取
date: 2026-03-10 02:51:00
categories: 技术
tags: [Docker, 代理, Linux, 运维]
---

## 问题

国内拉取 ghcr.io 的镜像经常超时或速度很慢。

## 注意：Docker不支持SOCKS5

Docker 的 `HTTP_PROXY` 只支持 `http://` 和 `https://`，不支持 `socks5://`。

如果只有 SOCKS5 代理：
1. 用支持 HTTP 的代理工具（如 Clash）
2. 或用 privoxy 转换

## 配置步骤

### 1. 创建配置目录

```bash
sudo mkdir -p /etc/systemd/system/docker.service.d
```

### 2. 写入代理配置

```bash
sudo tee /etc/systemd/system/docker.service.d/http-proxy.conf > /dev/null <<EOF
[Service]
Environment="HTTP_PROXY=http://127.0.0.1:7890"
Environment="HTTPS_PROXY=http://127.0.0.1:7890"
Environment="NO_PROXY=localhost,127.0.0.1"
EOF
```

### 3. 重启Docker

```bash
sudo systemctl daemon-reload
sudo systemctl restart docker
```

### 4. 验证

```bash
sudo systemctl show --property=Environment docker
docker pull ghcr.io/ssttkkl/shipyard-neo-ship-custom:main
```

## 常见代理端口

| 工具 | HTTP端口 | SOCKS5端口 |
|------|---------|-----------|
| Clash | 7890 | 7891 |
| v2rayA | 20171 | 20170 |
| Shadowsocks | - | 1080 |

## SOCKS5转HTTP（可选）

```bash
sudo apt install privoxy -y
echo "listen-address 127.0.0.1:8118" | sudo tee -a /etc/privoxy/config
echo "forward-socks5 / 127.0.0.1:1080 ." | sudo tee -a /etc/privoxy/config
sudo systemctl restart privoxy
```

然后用 `http://127.0.0.1:8118` 作为代理。

## 注意

1. 确保代理服务在运行
2. 端口号要对
3. 改完配置必须重启Docker
