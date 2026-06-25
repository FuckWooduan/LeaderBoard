#!/usr/bin/env bash
# 代理节点守护（每 30 分钟跑）：当前节点还活着就「不动」——保持稳定、不打断连接池常热连接。
# 仅当当前节点掉线/无延迟记录时，才故障转移到一个可用(HK/TW)节点。
# 说明：HTTP 延迟(到 hicloud)与「到游戏服的真实速度」并不强相关，故不盲目追最低延迟、不盲目轮换。
set -e
API=http://127.0.0.1:9090

result=$(API="$API" python3 - <<'PY'
import os, json, urllib.request
api = os.environ["API"]
sel = json.load(urllib.request.urlopen(api + "/proxies/proxy"))
now = sel.get("now", "")
cands = [x for x in sel.get("all", []) if x != "auto"]
allp = json.load(urllib.request.urlopen(api + "/proxies")).get("proxies", {})
def alive(n):
    h = allp.get(n, {}).get("history") or []
    return bool(h) and h[-1].get("delay", 0) > 0
# 当前节点(非 auto)仍活着 → 保持不动
if now and now != "auto" and alive(now):
    print("KEEP\t" + now)
else:
    live = [n for n in cands if alive(n)]
    print(("SWITCH\t" + live[0]) if live else "NONE\t")
PY
)
action="${result%%$'\t'*}"; node="${result#*$'\t'}"
case "$action" in
  KEEP)   echo "$(date +%F\ %H:%M) 当前节点正常，保持 -> $node" ;;
  SWITCH) curl -s -X PUT "$API/proxies/proxy" -H "Content-Type: application/json" -d "{\"name\":\"$node\"}" >/dev/null
          echo "$(date +%F\ %H:%M) 节点掉线，故障转移 -> $node" ;;
  *)      echo "$(date +%F\ %H:%M) 无可用节点" ;;
esac
