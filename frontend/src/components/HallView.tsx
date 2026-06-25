"use client";

/**
 * 频道大厅：主页全部区 → 点区看频道在线人数（SSE 实时）→ 点频道看房间列表（SSE 实时）。
 *
 * 三级视图由 URL query 驱动（/hall、/hall?server=、/hall?server=&channel=），浏览器前进/后退即切视图。
 * SSE 连接挂在 useEffect 上：server/channel 变化时 effect 重跑、cleanup 关掉旧 EventSource，
 * 天然替代老 static 版的「代次计数 + 登记表」防泄漏逻辑（React 生命周期已保证旧连接被关）。
 */

import { useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "@/lib/api";
import { SERVER_NAME, operatorColor } from "@/lib/servers";

type ServerCard = { id: number; name: string; available: boolean };
type Channel = {
  id: number;
  name: string;
  number: number;
  maxClient: number;
  percent: number;
  state: string;
  limitMinLv: number;
  limitMaxLv: number;
};
type Room = {
  roomId: number;
  displayId: number;
  name: string;
  mode: string;
  map: string;
  access: number;
  limit: number;
  status: number;
  encrypt: boolean;
  owner: string;
};

/** 频道拥挤度：后端 state 字符串 → [中文标签, 主色]。 */
const STATE_META: Record<string, [string, string]> = {
  free: ["空闲", "#34d399"],
  crowd: ["较多", "#fbbf24"],
  full: ["已满", "#f87171"],
  vip: ["VIP", "#a78bfa"],
};
const ROOM_STATUS = ["等待", "准备", "战斗中"];

function srvName(d: string): string {
  return SERVER_NAME[Number(d)] || `${d}区`;
}

function nowTime(): string {
  return new Date().toLocaleTimeString();
}

/** 区服卡片排序：按运营商 电信 → 双线 → 联通 分组，组内按区号升序。 */
const HALL_OP_ORDER: Record<string, number> = { 电信: 0, 双线: 1, 联通: 2 };
const HALL_CN = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九"];
function hallOrder(name: string): number {
  const op = HALL_OP_ORDER[name.slice(0, 2)] ?? 9;
  const num = HALL_CN.indexOf(name.replace(/区$/, "").slice(2));
  return op * 100 + (num < 0 ? 99 : num);
}

export function HallView() {
  const router = useRouter();
  const sp = useSearchParams();
  const server = sp.get("server") || "";
  const channel = sp.get("channel") || "";

  // 频道名缓存（点频道进房间后做标题；同一 /hall 路由内组件不卸载，ref 跨视图保留）。
  const chanNames = useRef<Record<number, string>>({});

  const goOverview = () => router.push("/hall");
  const goChannels = (district: number | string) => router.push(`/hall?server=${district}`);
  const goRooms = (district: string, cid: number) => router.push(`/hall?server=${district}&channel=${cid}`);

  if (!server) return <Overview onPick={goChannels} />;
  if (!channel) return <Channels server={server} chanNames={chanNames} onBack={goOverview} onPick={(cid) => goRooms(server, cid)} />;
  return (
    <Rooms
      server={server}
      channel={channel}
      title={chanNames.current[Number(channel)] || `频道 ${channel}`}
      onBack={() => goChannels(server)}
    />
  );
}

/** 主页：全部区卡片，已配侦察号(available)的可点进。 */
function Overview({ onPick }: { onPick: (district: number) => void }) {
  const [servers, setServers] = useState<ServerCard[] | null>(null);
  const [ts, setTs] = useState("");

  useEffect(() => {
    let live = true;
    api<ServerCard[]>("/api/public/channel-servers")
      .then((d) => {
        if (!live) return;
        setServers(d || []);
        setTs(nowTime());
      })
      .catch(() => live && setServers([]));
    return () => {
      live = false;
    };
  }, []);

  return (
    <section>
      <div style={toolbar}>
        <b style={{ fontSize: 16 }}>🏛️ 频道大厅</b>
        <span style={{ color: "var(--muted)", fontSize: 13 }}>点区服看各频道在线人数，再点频道看房间</span>
        {ts && <span style={{ fontSize: 13, color: "var(--muted)", marginLeft: "auto" }}>{ts}</span>}
      </div>
      {servers === null ? (
        <Loading />
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(168px,1fr))", gap: 12 }}>
          {[...servers].sort((a, b) => hallOrder(a.name) - hallOrder(b.name)).map((s) => {
            const [fg, bg] = operatorColor(s.name);
            return (
              <button
                key={s.id}
                disabled={!s.available}
                onClick={() => s.available && onPick(s.id)}
                className="card"
                style={{
                  textAlign: "left",
                  padding: "14px 16px",
                  cursor: s.available ? "pointer" : "default",
                  opacity: s.available ? 1 : 0.5,
                  borderColor: s.available ? bg.replace("0.14", "0.4").replace("0.16", "0.4") : "var(--border)",
                }}
              >
                <div style={{ fontWeight: 700, color: fg }}>{s.name}</div>
                <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 8 }}>
                  {s.available ? "点击查看频道 ›" : "未开放"}
                </div>
              </button>
            );
          })}
        </div>
      )}
    </section>
  );
}

/** 某区频道列表：channels SSE（后端每 5s 推一帧）。 */
function Channels({
  server,
  chanNames,
  onBack,
  onPick,
}: {
  server: string;
  chanNames: React.MutableRefObject<Record<number, string>>;
  onBack: () => void;
  onPick: (channelId: number) => void;
}) {
  const [channels, setChannels] = useState<Channel[] | null>(null);
  const [err, setErr] = useState("");
  const [ts, setTs] = useState("");

  useEffect(() => {
    setChannels(null);
    setErr("");
    let es: EventSource;
    try {
      es = new EventSource(`/api/public/channels/stream?server=${encodeURIComponent(server)}`);
    } catch {
      setErr("浏览器不支持实时推送");
      return;
    }
    es.addEventListener("channels", (ev) => {
      let list: Channel[] = [];
      try {
        list = JSON.parse((ev as MessageEvent).data);
      } catch {
        /* 坏帧忽略 */
      }
      list.forEach((c) => (chanNames.current[c.id] = c.name));
      setChannels(list);
      setTs(nowTime());
    });
    es.addEventListener("error", () => {
      // 非200(未配号/拒绝)→ EventSource CLOSED 且不重连；CONNECTING 是正常抖动，交给浏览器自愈。
      if (es.readyState === 2) {
        es.close();
        setErr("该区暂不可用");
      }
    });
    return () => es.close();
  }, [server, chanNames]);

  return (
    <section>
      <div style={toolbar}>
        <button onClick={onBack}>‹ 全部区</button>
        <b style={{ fontSize: 16 }}>{srvName(server)} · 频道</b>
        {ts && <span style={{ fontSize: 13, color: "var(--muted)", marginLeft: "auto" }}>实时 · {ts}</span>}
      </div>
      <div className="card" style={{ padding: 0 }}>
        {channels === null && !err ? (
          <Loading />
        ) : err ? (
          <Empty text={err} />
        ) : !channels || channels.length === 0 ? (
          <Empty text="暂无频道" />
        ) : (
          <div className="table-wrap" style={{ border: "none" }}>
            <table className="data">
              <thead>
                <tr>
                  <th>频道</th>
                  <th className="num">在线 / 容量</th>
                  <th>拥挤度</th>
                  <th>等级</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {channels.map((c) => {
                  const [label, color] = STATE_META[c.state] || ["", "var(--muted)"];
                  const lvl =
                    c.limitMinLv > 0 || (c.limitMaxLv > 0 && c.limitMaxLv < 999)
                      ? `Lv ${c.limitMinLv}–${c.limitMaxLv}`
                      : "不限";
                  return (
                    <tr key={c.id} style={{ cursor: "pointer" }} onClick={() => onPick(c.id)}>
                      <td>
                        <b>{c.name}</b>
                      </td>
                      <td className="num">
                        {(c.number || 0).toLocaleString()} / {(c.maxClient || 0).toLocaleString()}
                      </td>
                      <td>
                        <span style={chanbar}>
                          <i style={{ display: "block", height: "100%", width: `${c.percent || 0}%`, background: color }} />
                        </span>{" "}
                        <span style={{ fontSize: 12, fontWeight: 700, color }}>{label}</span>
                      </td>
                      <td style={{ color: "var(--muted)" }}>{lvl}</td>
                      <td style={{ color: "var(--primary)" }}>看房间 ›</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}

/** 某频道房间列表：rooms SSE（建连即推全量，之后增量毫秒级推帧）。 */
function Rooms({
  server,
  channel,
  title,
  onBack,
}: {
  server: string;
  channel: string;
  title: string;
  onBack: () => void;
}) {
  const [rooms, setRooms] = useState<Room[] | null>(null);
  const [err, setErr] = useState("");
  const [ts, setTs] = useState("");

  useEffect(() => {
    setRooms(null);
    setErr("");
    let live = true;
    let es: EventSource;
    try {
      es = new EventSource(`/api/public/rooms/stream?server=${encodeURIComponent(server)}&channel=${encodeURIComponent(channel)}`);
    } catch {
      setErr("浏览器不支持实时推送");
      return;
    }
    es.addEventListener("rooms", (ev) => {
      let list: Room[] = [];
      try {
        list = JSON.parse((ev as MessageEvent).data);
      } catch {
        /* 坏帧忽略 */
      }
      setRooms(list);
      setTs(nowTime());
    });
    es.addEventListener("unavailable", (ev) => {
      // 进频道后发现不可服务(授权失败/等级不足)：后端推 unavailable + 原因，保持连接(后台会重试)。
      setErr(((ev as MessageEvent).data && String((ev as MessageEvent).data)) || "该频道暂不可用");
    });
    es.addEventListener("error", () => {
      if (es.readyState !== 2) return; // CONNECTING 抖动交给浏览器自愈
      es.close();
      // 非200 拒绝 → 取精确原因展示
      api<{ message?: string }>(`/api/public/rooms?server=${encodeURIComponent(server)}&channel=${encodeURIComponent(channel)}`)
        .then((d) => live && setErr(d?.message || "该频道暂不可用"))
        .catch(() => live && setErr("该频道暂不可用"));
    });
    return () => {
      live = false;
      es.close();
    };
  }, [server, channel]);

  return (
    <section>
      <div style={toolbar}>
        <button onClick={onBack}>‹ 频道列表</button>
        <b style={{ fontSize: 16 }}>
          {srvName(server)} · {title} · 房间
        </b>
        {ts && <span style={{ fontSize: 13, color: "var(--muted)", marginLeft: "auto" }}>实时 · {ts}</span>}
      </div>
      <div className="card" style={{ padding: 0 }}>
        {rooms === null && !err ? (
          <Loading />
        ) : err ? (
          <Empty text={err} />
        ) : !rooms || rooms.length === 0 ? (
          <Empty text="该频道暂无房间" />
        ) : (
          <div className="table-wrap" style={{ border: "none" }}>
            <table className="data">
              <thead>
                <tr>
                  <th className="num">房号</th>
                  <th>房间名</th>
                  <th>玩法</th>
                  <th>地图</th>
                  <th className="num">人数</th>
                  <th>状态</th>
                  <th>房主</th>
                </tr>
              </thead>
              <tbody>
                {rooms.map((r) => (
                  <tr key={r.roomId}>
                    <td className="num">
                      {r.displayId}
                      {r.encrypt ? " 🔒" : ""}
                    </td>
                    <td>{r.name}</td>
                    <td>{r.mode}</td>
                    <td style={{ color: "var(--muted)" }}>{r.map}</td>
                    <td className="num">
                      {r.access}/{r.limit}
                    </td>
                    <td>{ROOM_STATUS[r.status] ?? "—"}</td>
                    <td style={{ color: "var(--muted)" }}>{r.owner}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}

function Loading() {
  return (
    <div style={{ display: "flex", justifyContent: "center", padding: "40px 0" }}>
      <span className="spinner" />
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <div style={{ textAlign: "center", color: "var(--muted)", padding: "36px 16px", fontSize: 14 }}>{text}</div>;
}

const toolbar: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: 12,
  flexWrap: "wrap",
  marginBottom: 14,
};

const chanbar: React.CSSProperties = {
  width: 120,
  height: 12,
  background: "var(--hover)",
  borderRadius: 7,
  overflow: "hidden",
  display: "inline-block",
  verticalAlign: "middle",
};
