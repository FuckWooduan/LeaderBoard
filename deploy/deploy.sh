#!/usr/bin/env bash
# =============================================================================
# rank-harvester 自动部署（无 CI）：拉取最新代码 → 构建 bootJar → 重启 systemd。
# 用法： bash deploy/deploy.sh
# 可用环境变量覆盖：APP_DIR / SERVICE / BRANCH
# =============================================================================
set -euo pipefail

APP_DIR="${APP_DIR:-/www/wwwroot/rank-harvester}"
SERVICE="${SERVICE:-rank-harvester}"
BRANCH="${BRANCH:-main}"

cd "$APP_DIR"

echo "[deploy] 拉取最新代码（origin/$BRANCH，直接对齐远端，丢弃本地改动）"
git fetch --all --prune
git reset --hard "origin/$BRANCH"   # 部署目录不应有本地改动；如需保留改动请改用 git pull --ff-only

echo "[deploy] 构建 bootJar"
chmod +x ./gradlew
./gradlew --no-daemon clean bootJar

echo "[deploy] 固定 jar 路径 -> $APP_DIR/app.jar"
JAR="$(ls -t build/libs/*.jar | head -1)"
cp -f "$JAR" "$APP_DIR/app.jar"

echo "[deploy] 重启 $SERVICE"
sudo systemctl restart "$SERVICE"

echo "[deploy] 等待健康检查"
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    echo "[deploy] ✅ 健康检查通过"
    curl -s http://127.0.0.1:8080/actuator/health; echo
    exit 0
  fi
  sleep 2
done
echo "[deploy] ❌ 健康检查超时，查看： sudo journalctl -u $SERVICE -n 100 --no-pager"
exit 1
