#!/usr/bin/env bash
# =============================================================================
# 单容器看门狗：同时托管 sing-box + Spring Boot(app.jar) + Next.js(web) + NapCat(QQ活动推送)。
# 任一进程退出 → 仅该进程 5s 后自动重启（其它不受影响）。这点对 NapCat 尤其关键：
# QQ 掉线/崩溃 → NapCat 进程退出 → 看门狗重启 → 复用挂载的登录态自动快速登录，无需重扫码。
# 收到 SIGTERM/SIGINT → 转发给整个进程组，优雅关停。
# =============================================================================
set -uo pipefail

echo "[super] 单容器启动：app + web + sing-box + napcat"

# 单进程崩溃重启循环。$1=名称，其余=命令。
run_loop() {
  local name="$1"; shift
  while true; do
    echo "[super] 启动 $name"
    "$@"
    local code=$?
    echo "[super] $name 退出 code=$code，5s 后重启"
    sleep 5
  done
}

# ── sing-box（本地 SOCKS5 出口）──────────────────────────────────────────────
# 始终在看门狗循环里跑：后端按后台 trojan 节点重生成 config.json 后会 `pkill -x sing-box`，
# 进程退出 → run_loop 5s 内用新配置自动重启（热重载）。配置暂缺时 sing-box 会快速失败并重试，
# 待后端 @PostConstruct 写出配置后下一次重试即起。
run_loop sing-box sing-box run -c /etc/sing-box/config.json &

# ── Spring Boot 后端（堆上限由 JAVA_OPTS 控制；单容器多进程，建议固定 -Xmx）──────
if [ -f /app/app.jar ]; then
  run_loop app java --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Shanghai ${JAVA_OPTS:-} -jar /app/app.jar &
else
  echo "[super] WARN: /app/app.jar 不存在，跳过后端"
fi

# ── Next.js SSR（standalone server.js）──────────────────────────────────────
if [ -f /app/web/server.js ]; then
  (
    export PORT="${WEB_PORT:-3100}" HOSTNAME=127.0.0.1 \
      API_BASE="${API_BASE:-http://127.0.0.1:8080}" \
      MCP_API_KEY="${MCP_API_KEY:-}" \
      NAPCAT_HTTP="${NAPCAT_HTTP:-http://127.0.0.1:3010}" \
      NAPCAT_TOKEN="${NAPCAT_TOKEN:-}" \
      QQBOT_PUSH_TOKEN="${QQBOT_PUSH_TOKEN:-}" \
      QQBOT_OWNER_QQ="${QQBOT_OWNER_QQ:-}" \
      SPRING_DATA_REDIS_HOST="${SPRING_DATA_REDIS_HOST:-127.0.0.1}" \
      SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-6379}" \
      SPRING_DATA_REDIS_PASSWORD="${SPRING_DATA_REDIS_PASSWORD:-}"
    cd /app/web
    run_loop web node server.js
  ) &
else
  echo "[super] WARN: /app/web/server.js 不存在，跳过前端"
fi

# ── WebUI 登录 token 固定化（NapCat 会生成随机 token，这里强制为已知值，方便扫码登录）──
WEBUI_JSON=/app/napcat/config/webui.json
if [ -f "$WEBUI_JSON" ] && [ -n "${WEBUI_TOKEN:-}" ]; then
  node -e "const f='$WEBUI_JSON',fs=require('fs');try{const j=JSON.parse(fs.readFileSync(f,'utf8'));j.token=process.env.WEBUI_TOKEN;j.host='0.0.0.0';fs.writeFileSync(f,JSON.stringify(j,null,2));console.log('[super] webui token 已固定');}catch(e){console.log('[super] webui token 固定失败:',e.message);}" || true
fi

# ── 确保 OneBot 网络配置就位（兼容 NapCat 读 onebot11.json 或登录后的 onebot11_<uin>.json）──
if [ -f /app/templates/strikegod.json ]; then
  cp -f /app/templates/strikegod.json /app/napcat/config/onebot11.json 2>/dev/null || true
  if [ -n "${ACCOUNT:-}" ]; then
    cp -f /app/templates/strikegod.json "/app/napcat/config/onebot11_${ACCOUNT}.json" 2>/dev/null || true
  fi
fi

# ── NapCat（原镜像 entrypoint：反容器检测 + Xvfb + 启动 QQ；前台阻塞→崩溃自动重登）──
run_loop napcat bash /app/entrypoint.sh &

# ── 掉线自动重登看门狗 ─────────────────────────────────────────────────────────
# 轮询 OneBot get_status；连续检测到 online=false → 杀掉 QQ 进程，由上面的 napcat run_loop
# 重新拉起并用挂载的登录态快速重登（无需重新扫码）。区别于「进程崩溃重启」，这是「账号掉线重登」。
(
  offline=0
  while true; do
    sleep 60
    R=$(curl -s -m 5 http://127.0.0.1:3010/get_status 2>/dev/null || true)
    if printf '%s' "$R" | grep -q '"online":false'; then
      offline=$((offline + 1))
      echo "[watchdog] 检测到 QQ 掉线 ${offline}/2"
      if [ "$offline" -ge 2 ]; then
        echo "[watchdog] QQ 持续掉线，重启 QQ 触发快速重登"
        pkill -9 -f '/opt/QQ/qq' 2>/dev/null || true
        offline=0
      fi
    elif printf '%s' "$R" | grep -q '"online":true'; then
      offline=0
    fi
  done
) &

shutdown() {
  echo "[super] 收到关停信号，转发 TERM 给进程组"
  kill -TERM 0 2>/dev/null || true
  wait 2>/dev/null || true
  exit 0
}
trap shutdown TERM INT

wait
