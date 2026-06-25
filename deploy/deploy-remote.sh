#!/usr/bin/env bash
# =============================================================================
# rank-harvester 一键远程部署（当前真实流程）：
#   本地构建（bootJar + Next standalone）→ rsync 上传 → 服务器切换 + 容器重启 → 健康检查。
#
# 用法：  bash deploy/deploy-remote.sh [jar|web|all]   （默认 all）
# 前提：  ~/.ssh/config 已配置 Host "server"（root@部署机）。
#
# 服务器目录约定（/www/wwwroot/rank-harvester）：
#   app.jar   后端（strikegod 容器以只读挂载运行）
#   web/      Next.js standalone（strikegod-web 容器，127.0.0.1:3100）
#   deploy/   compose / .env（机密在 deploy/.env，不入库）
# nginx：页面 → 127.0.0.1:3100；/api、/actuator → 127.0.0.1:8080（见 nginx-example.com.conf）。
# =============================================================================
set -euo pipefail

TARGET="${1:-all}"
HOST="${HOST:-server}"
REMOTE_DIR="/www/wwwroot/rank-harvester"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT"

if [[ "$TARGET" == "jar" || "$TARGET" == "all" ]]; then
  echo "[deploy] 构建后端 bootJar"
  ./gradlew bootJar -q
  echo "[deploy] 上传 app.jar"
  rsync -az --partial --inplace --timeout=60 build/libs/rank-harvester-*.jar "$HOST:$REMOTE_DIR/app.jar.new"
fi

if [[ "$TARGET" == "web" || "$TARGET" == "all" ]]; then
  echo "[deploy] 构建前端（Next standalone）"
  rm -rf frontend/.next
  (cd frontend && npm run build)
  rm -rf frontend/.next/standalone/public frontend/.next/standalone/.next/static
  cp -r frontend/public frontend/.next/standalone/public
  cp -r frontend/.next/static frontend/.next/standalone/.next/static
  COPYFILE_DISABLE=1 tar -C frontend/.next/standalone \
    --exclude='._*' --exclude='.DS_Store' \
    -czf /tmp/skg-web.tgz . 2>/dev/null
  echo "[deploy] 上传 web 包"
  rsync -az --partial /tmp/skg-web.tgz "$HOST:/tmp/skg-web.tgz"
fi

echo "[deploy] 上传 compose 与镜像构建文件"
rsync -az deploy/docker-compose.runtime.yml deploy/Dockerfile.runtime \
  deploy/super-entrypoint.sh deploy/napcat-onebot11.json \
  "$HOST:$REMOTE_DIR/deploy/"

echo "[deploy] 服务器切换 + 重启"
ssh "$HOST" REMOTE_DIR="$REMOTE_DIR" TARGET="$TARGET" 'bash -s' <<'REMOTE'
set -euo pipefail
cd "$REMOTE_DIR"
if [[ ( "$TARGET" == "jar" || "$TARGET" == "all" ) && -f app.jar.new ]]; then
  cp -f app.jar app.jar.bak 2>/dev/null || true
  mv app.jar.new app.jar
fi
if [[ ( "$TARGET" == "web" || "$TARGET" == "all" ) && -f /tmp/skg-web.tgz ]]; then
  rm -rf web.new && mkdir -p web.new && tar -C web.new -xzf /tmp/skg-web.tgz 2>/dev/null
  find web.new -name '._*' -o -name '.DS_Store' -delete 2>/dev/null || true
  rm -rf web.old && { [ -d web ] && mv web web.old || true; } && mv web.new web
fi
# 单容器：app+web+sing-box 同在 strikegod。--build 让 Dockerfile/super-entrypoint 变更生效
# （镜像层有缓存，纯代码部署很快）；--force-recreate 保证重载绑定挂载的新 jar/web（单次重启，不再额外 docker restart）；
# --remove-orphans 清掉旧的孤儿容器。
docker compose -f deploy/docker-compose.runtime.yml up -d --build --force-recreate --remove-orphans

echo "[deploy] 等待健康检查"
ok=""
for i in $(seq 1 50); do
  S=$(curl -s -m 3 http://127.0.0.1:8080/actuator/health/liveness 2>/dev/null || true)
  A=$(curl -s -o /dev/null -w "%{http_code}" -m 5 http://127.0.0.1:8080/api/public/weapon/status 2>/dev/null || true)
  W=$(curl -s -o /dev/null -w "%{http_code}" -m 3 http://127.0.0.1:3100/ 2>/dev/null || true)
  if [[ "$S" == *'"UP"'* && "$A" == "200" && "$W" == "200" ]]; then ok=1; break; fi
  sleep 3
done
if [[ -n "$ok" ]]; then
  echo "[deploy] ✅ 后端 liveness UP + 业务 API 200 + 前端 200"
  rm -rf web.old
else
  echo "[deploy] ❌ 健康检查超时：后端=$S API=$A 前端=$W"
  exit 1
fi
REMOTE
echo "[deploy] 完成"
