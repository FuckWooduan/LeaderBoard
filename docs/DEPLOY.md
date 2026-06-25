# rank-harvester 部署文档

## 架构概览

```
┌─────────────────────────────────────────────────────┐
│                   Docker Compose                     │
│                                                     │
│  ┌──────────┐   内网   ┌──────────┐                │
│  │  napcat  │◄────────►│   app    │                │
│  │ OneBot   │  :3000   │ Spring   │                │
│  │ HTTP API │          │  Boot    │                │
│  └──────────┘          │ DuckDB   │                │
│                        └────┬─────┘                │
│  ┌──────────┐               │ 内网                 │
│  │  redis   │◄──────────────┘                     │
│  │  :6379   │                                      │
│  └──────────┘                                      │
└─────────────────────────────────────────────────────┘
        │ 127.0.0.1:8080 (actuator)
        └── 宿主机
```

| 容器     | 镜像                          | 职责                                    |
|--------|-----------------------------|-----------------------------------------|
| redis  | redis:7-alpine              | 任务队列 / IPC pub-sub / 热快照缓存      |
| napcat | mlikiowa/napcat-docker      | QQ OneBot v11 HTTP 网关（扫码登录）      |
| app    | 本地构建                      | 排行榜抓取主服务，含嵌入式 DuckDB         |

---

## 前置条件

- Docker >= 24 + Docker Compose v2
- 服务器上有 QQ 机器人账号，用于 NapCat 登录

---

## 首次部署步骤

### 1. 准备账号配置

```bash
cp config/accounts.example.json config/accounts.json
# 编辑 config/accounts.json，填入游戏账号列表
```

### 2. 首次 NapCat 扫码登录

NapCat 需要扫码完成首次登录，登录态会持久化到 `./deploy/napcat/`。

```bash
# 仅启动 napcat，查看日志中的二维码
docker compose up napcat

# 使用手机 QQ 扫码登录，看到"登录成功"后 Ctrl-C
```

登录成功后，登录态保存在 `./deploy/napcat/`，后续启动无需重复扫码。

### 3. 配置 NapCat HTTP Server

napcat 启动后访问 WebUI（仅宿主可达）：

```
http://127.0.0.1:6099
```

在 WebUI 中配置：

1. **网络配置** → **HTTP Server**
   - 启用：开启
   - 端口：`3000`
   - access_token：设置一个随机字符串（例如 `your_access_token_here`）

2. 记录 access_token，下一步需要填入环境变量。

### 4. 配置环境变量

创建 `.env` 文件（或直接修改 `docker-compose.yml` 的 environment 段）：

```bash
# .env
NAPCAT_QQ_ACCOUNT=你的机器人QQ号
QQBOT_ACCESS_TOKEN=your_access_token_here   # 与 NapCat 里设置的一致
QQBOT_GROUP_IDS=123456789,987654321          # 推送目标群号，逗号分隔
```

### 5. 全量启动

```bash
docker compose up -d --build
```

### 6. 验证服务健康

```bash
# 检查所有容器状态
docker compose ps

# 检查 app 健康端点
curl http://127.0.0.1:8080/actuator/health

# 查看 app 日志
docker compose logs -f app
```

健康响应示例：

```json
{"status":"UP"}
```

---

## DuckDB 数据存储

DuckDB 嵌入式数据库文件落在宿主机 `./data/` 目录，通过 volume 挂载进容器 `/app/data/`。

```bash
# 查看数据文件
ls -lh ./data/

# 备份
cp -r ./data/ ./data-backup-$(date +%Y%m%d)/
```

---

## 日常运维

### 重启服务

```bash
docker compose restart app
```

### 更新镜像重新构建

```bash
docker compose up -d --build app
```

### 查看日志

```bash
# 实时跟踪
docker compose logs -f app

# 最近 100 行
docker compose logs --tail=100 app
```

### 停止所有服务

```bash
docker compose down
# 保留数据 volume：不加 -v
# 清空 redis 数据：docker compose down -v
```

---

## access_token 对齐说明

NapCat HTTP Server 的 `access_token` 与 app 的 `RANKHARVESTER_QQBOT_ACCESSTOKEN` 必须完全一致：

| 配置位置                                       | 值                          |
|----------------------------------------------|-----------------------------|
| NapCat WebUI → HTTP Server → access_token    | `your_access_token_here`    |
| docker-compose.yml RANKHARVESTER_QQBOT_ACCESSTOKEN | `your_access_token_here` |

不一致时 app 推送会收到 `401 Unauthorized`，日志中打 warn 级别提示。

---

## 故障排查

| 现象                              | 排查方向                                              |
|----------------------------------|------------------------------------------------------|
| `app` 无法连接 redis              | `docker compose ps redis` 确认健康；检查 SPRING_DATA_REDIS_HOST=redis |
| QQ 推送 401                       | NapCat access_token 与 RANKHARVESTER_QQBOT_ACCESSTOKEN 不一致 |
| QQ 推送超时                       | 检查 napcat 容器是否运行；napcat HTTP Server 是否在 3000 端口启动 |
| NapCat 掉线需重新登录              | `docker compose restart napcat`，若会话失效需重新扫码  |
| DuckDB 文件损坏                   | 停止 app，备份 data/，从最近快照恢复                   |
