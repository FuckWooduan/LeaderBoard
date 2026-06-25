# 部署说明（无 CI，直接拉取部署）

## 一、域名 & Cloudflare

**只需要一个域名：`example.com`**（含 `www` 可选）。所有东西（前端、后台 `/api`、健康检查）都在这一个域名下，由 Spring(:8080) 统一服务、nginx 反代。

| 用途 | 域名/入口 | Cloudflare | 说明 |
|------|-----------|-----------|------|
| 前端 + 后台 API | `example.com`（`/` 与 `/api`） | ✅ 橙云代理 | A 记录指向服务器 IP；CF 代理 80/443 |
| QQ 机器人(NapCat) | 无需入站域名 | — | NapCat 出站连 QQ；OneBot HTTP 仅 `127.0.0.1:3000`（app 内部调用） |
| NapCat WebUI(6099) | 不公开 | ❌ 不解析/不过 CF | 仅 `127.0.0.1` 或 SSH 隧道访问，避免暴露 |

**出站访问（服务器需能直连，绝对不要走 Cloudflare）**：游戏 TCP 服（你的服务器列表 host:port）、4399 大厅（flashvars/pauth 登录链）、资源 CDN（resurl）。这些是抓取/登录/活动解析的出站连接，CF 只管入站 web。

DNS：在 Cloudflare 加 `example.com` A 记录 → 服务器公网 IP，橙云开启。SSL 模式建议 **Full (strict)** + 安装 CF Origin 证书到 nginx 443；图省事可先用 **Flexible**（访客 HTTPS、回源 80，本配置即可）。

## 二、反向代理

`deploy/nginx-example.com.conf`：`example.com` → `127.0.0.1:8080`，一个 location 覆盖全部（Spring 同时出前端 SPA 和 `/api`）。宝塔：站点→配置文件粘贴。

## 三、首次安装

```bash
# 1. 代码（私有仓库）
git clone <你的私有仓库> /www/wwwroot/rank-harvester
cd /www/wwwroot/rank-harvester

# 2. 环境变量与种子
cp deploy/.env.example deploy/.env && vi deploy/.env
cp /path/to/public.game_account.sql deploy/game_account.sql   # 账号种子

# 3. systemd 服务
sudo cp deploy/rank-harvester.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable rank-harvester

# 4. Redis（本机，口令见 application.yml）
#   redis-server --requirepass CHANGE_ME_REDIS_PASSWORD  （或 BT 安装 Redis 后设密码）

# 5. nginx：粘贴 deploy/nginx-example.com.conf 到站点配置并 reload

# 6. 首次部署
bash deploy/deploy.sh
```

## 四、日常部署（拉最新直接上）

```bash
bash deploy/deploy.sh          # git reset --hard origin/main → bootJar → 重启 → 健康检查
sudo journalctl -u rank-harvester -f   # 看日志
```

> `deploy.sh` 用 `git reset --hard origin/main`：部署目录不保留本地改动，永远对齐远端最新。
> 需要 JDK 25 与可访问的 Redis；前端构建产物随 jar 一起打包（见前端阶段）。
