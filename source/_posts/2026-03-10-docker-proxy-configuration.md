---
title: Docker 配置 HTTP 代理加速镜像拉取
date: 2026-03-10 02:51:00
categories: 技术
tags: [Docker, 代理, Linux, 运维]
---

## 问题背景

在国内使用 Docker 拉取 GitHub Container Registry (ghcr.io) 的镜像时，经常遇到速度极慢甚至超时的问题。本文记录如何为 Docker 配置 HTTP 代理来解决这个问题。

## 关键点：Docker 不支持 SOCKS5

Docker 的 `HTTP_PROXY` 环境变量**不支持** `socks5://` 协议，只支持 `http://` 和 `https://`。

如果你只有 SOCKS5 代理，需要：
1. 使用支持 HTTP 协议的代理工具（如 Clash）
2. 或使用 privoxy 将 SOCKS5 转换为 HTTP

## 配置步骤

### 1. 创建配置目录

```bash
sudo mkdir -p /etc/systemd/system/docker.service.d
```

### 2. 创建代理配置文件

```bash
sudo tee /etc/systemd/system/docker.service.d/http-proxy.conf > /dev/null <<EOF
[Service]
Environment="HTTP_PROXY=http://127.0.0.1:7890"
Environment="HTTPS_PROXY=http://127.0.0.1:7890"
Environment="NO_PROXY=localhost,127.0.0.1"
EOF
```

**参数说明**：
- `HTTP_PROXY` - HTTP 流量代理
- `HTTPS_PROXY` - HTTPS 流量代理（Docker 拉取镜像主要用这个）
- `NO_PROXY` - 不走代理的地址列表

### 3. 重载配置并重启 Docker

```bash
sudo systemctl daemon-reload
sudo systemctl restart docker
```

### 4. 验证配置

```bash
# 查看 Docker 环境变量
sudo systemctl show --property=Environment docker

# 测试拉取镜像
docker pull ghcr.io/ssttkkl/shipyard-neo-ship-custom:main
```

## 常见代理端口

| 工具 | HTTP 端口 | SOCKS5 端口 |
|------|----------|-------------|
| Clash | 7890 | 7891 |
| v2rayA | 20171 | 20170 |
| Shadowsocks | - | 1080 |

## SOCKS5 转 HTTP（可选）

如果只有 SOCKS5 代理，可以使用 privoxy：

```bash
# 安装 privoxy
sudo apt install privoxy -y

# 配置转发
echo "listen-address 127.0.0.1:8118" | sudo tee -a /etc/privoxy/config
echo "forward-socks5 / 127.0.0.1:1080 ." | sudo tee -a /etc/privoxy/config

# 重启服务
sudo systemctl restart privoxy

# 使用 http://127.0.0.1:8118 作为 HTTP 代理
```

## 注意事项

1. **确保代理服务运行**：配置前确认代理工具（Clash/v2ray）正在运行
2. **端口号要正确**：不同工具的默认端口可能不同
3. **重启 Docker**：配置修改后必须重启 Docker 才能生效
4. **安全性**：如果 Docker 绑定到公网 IP，注意代理配置的安全性

## 总结

- Docker 只支持 HTTP/HTTPS 代理，不支持 SOCKS5
- 配置文件位置：`/etc/systemd/system/docker.service.d/http-proxy.conf`
- 修改后需要 `daemon-reload` + `restart docker`
- 可以用 privoxy 将 SOCKS5 转换为 HTTP 代理
