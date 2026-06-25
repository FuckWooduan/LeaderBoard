/**
 * 人机验证封装：极验 GeeTest v4 与 Cloudflare Turnstile 并列。
 *
 * 弹窗默认走极验（对中国用户友好），底部「改用 Cloudflare 验证」可切到 Turnstile；两者任一通过即可，
 * 后端 {@code CaptchaService} 任一校验通过即放行。调用 {@link withCaptcha} resolve 出的对象 spread 进
 * 请求体即可：极验给 {@code gtPayload}（getValidate() 的 JSON），Turnstile 给 {@code cfToken}。
 * 用户关闭/出错则 reject；两者都未启用时直接 resolve `{}` 放行。
 */

import { api } from "./api";

export interface CaptchaParams {
  cfToken?: string; // Cloudflare Turnstile 一次性 token
  gtPayload?: string; // 极验 GeeTest v4 getValidate() 的 JSON 串
}

type CaptchaConfig = {
  turnstile: { enabled: boolean; siteKey?: string };
  geetest: { enabled: boolean; captchaId?: string };
} | null;

type Method = "geetest" | "turnstile";

const TURNSTILE_SRC = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
const GEETEST_SRC = "https://static.geetest.com/v4/gt4.js";

interface TurnstileApi {
  render(el: HTMLElement, opts: Record<string, unknown>): string;
  remove(id: string): void;
}
interface GeetestCaptcha {
  appendTo(target: string | HTMLElement): void;
  getValidate(): Record<string, unknown>;
  onSuccess(cb: () => void): void;
  onError(cb: (e: unknown) => void): void;
  destroy?(): void;
}
type GeetestInit = (
  config: Record<string, unknown>,
  handler: (captcha: GeetestCaptcha) => void,
) => void;

const win = () =>
  window as unknown as { turnstile?: TurnstileApi; initGeetest4?: GeetestInit };

let config: CaptchaConfig = null;
const scripts: Record<string, Promise<void> | undefined> = {};
let slotSeq = 0;

/** 页面加载后预拉配置 + 预载默认验证脚本。 */
export async function initCaptcha(): Promise<void> {
  if (config) return;
  try {
    config = await api("/api/public/captcha-config");
    if (config?.geetest?.enabled && config.geetest.captchaId) void loadScript(GEETEST_SRC, "initGeetest4");
    else if (config?.turnstile?.enabled && config.turnstile.siteKey) void loadScript(TURNSTILE_SRC, "turnstile");
  } catch {
    /* 拉取失败 → 提交时再重试 */
  }
}

export function captchaEnabled(): boolean {
  return Boolean(
    (config?.geetest?.enabled && config.geetest.captchaId) ||
      (config?.turnstile?.enabled && config.turnstile.siteKey),
  );
}

function loadScript(src: string, globalKey: keyof ReturnType<typeof win>): Promise<void> {
  const cached = scripts[src];
  if (cached) return cached;
  const p = new Promise<void>((resolve, reject) => {
    if (win()[globalKey]) {
      resolve();
      return;
    }
    const s = document.createElement("script");
    s.src = src;
    s.async = true;
    s.onload = () => resolve();
    s.onerror = () => {
      scripts[src] = undefined;
      reject(new Error("验证组件加载失败，请检查网络后重试"));
    };
    document.head.appendChild(s);
  });
  scripts[src] = p;
  return p;
}

/** 弹出验证弹窗，通过后 resolve；未启用直接 resolve({})；用户关闭/出错 reject。 */
export async function withCaptcha(): Promise<CaptchaParams> {
  if (!config) await initCaptcha();
  const gtOk = Boolean(config?.geetest?.enabled && config.geetest.captchaId);
  const tsOk = Boolean(config?.turnstile?.enabled && config.turnstile.siteKey);
  if (!gtOk && !tsOk) return {};

  return new Promise<CaptchaParams>((resolve, reject) => {
    const ui = buildModal(gtOk && tsOk);
    let settled = false;
    let disposeWidget: (() => void) | null = null;

    const cleanup = () => {
      try {
        disposeWidget?.();
      } catch {
        /* widget 已销毁 */
      }
      ui.overlay.remove();
    };
    const done = (params: CaptchaParams) => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(params);
    };
    const fail = (msg: string) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(new Error(msg));
    };

    ui.cancel.onclick = () => fail("已取消验证");
    ui.overlay.onclick = (e) => {
      if (e.target === ui.overlay) fail("已取消验证");
    };

    const mount = async (method: Method) => {
      try {
        disposeWidget?.();
      } catch {
        /* 上一个 widget 已销毁 */
      }
      ui.slot.innerHTML = "";
      ui.setSwitchLabel(method === "geetest" ? "改用 Cloudflare 验证" : "改用极验滑块验证");
      disposeWidget =
        method === "geetest"
          ? await mountGeetest(ui.slot, done, fail)
          : await mountTurnstile(ui.slot, done, fail);
    };

    if (gtOk && tsOk) {
      let current: Method = "geetest";
      ui.switchLink.onclick = () => {
        current = current === "geetest" ? "turnstile" : "geetest";
        void mount(current);
      };
    }
    void mount(gtOk ? "geetest" : "turnstile").catch((e: unknown) =>
      fail(e instanceof Error ? e.message : "验证组件初始化失败"),
    );
  });
}

/** 渲染 Turnstile widget；返回销毁函数。 */
async function mountTurnstile(
  slot: HTMLElement,
  done: (p: CaptchaParams) => void,
  fail: (msg: string) => void,
): Promise<() => void> {
  await loadScript(TURNSTILE_SRC, "turnstile");
  const ts = win().turnstile;
  if (!ts) {
    fail("验证组件未就绪，请稍后重试");
    return () => {};
  }
  const widgetId = ts.render(slot, {
    sitekey: config!.turnstile.siteKey,
    theme: document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark",
    language: "zh-cn",
    callback: (token: string) => done({ cfToken: token }),
    "error-callback": () => fail("验证出错，请重试"),
    "expired-callback": () => fail("验证已过期，请重试"),
  });
  return () => ts.remove(widgetId);
}

/** 渲染极验 GeeTest v4（popup：卡片内出验证按钮，点开滑块）；返回销毁函数。 */
async function mountGeetest(
  slot: HTMLElement,
  done: (p: CaptchaParams) => void,
  fail: (msg: string) => void,
): Promise<() => void> {
  await loadScript(GEETEST_SRC, "initGeetest4");
  const init = win().initGeetest4;
  if (!init) {
    fail("验证组件未就绪，请稍后重试");
    return () => {};
  }
  const id = `gt4-slot-${++slotSeq}`;
  slot.id = id;
  let captcha: GeetestCaptcha | null = null;
  init(
    {
      captchaId: config!.geetest.captchaId,
      product: "popup",
      language: "zho",
    },
    (c) => {
      captcha = c;
      c.appendTo(`#${id}`);
      c.onSuccess(() => done({ gtPayload: JSON.stringify(c.getValidate()) }));
      c.onError(() => fail("验证出错，请重试"));
    },
  );
  return () => captcha?.destroy?.();
}

interface ModalUi {
  overlay: HTMLDivElement;
  slot: HTMLDivElement;
  cancel: HTMLButtonElement;
  switchLink: HTMLButtonElement;
  setSwitchLabel: (text: string) => void;
}

/** 构建遮罩 + 居中卡片：提示 / 验证位 / 切换链接(双验证时) / 取消。 */
function buildModal(showSwitch: boolean): ModalUi {
  const overlay = document.createElement("div");
  // z-index 低于极验弹层(9999)，确保极验滑块弹窗叠在卡片之上
  overlay.style.cssText =
    "position:fixed;inset:0;z-index:9000;background:rgba(2,6,23,0.66);backdrop-filter:blur(4px);" +
    "display:flex;align-items:center;justify-content:center;padding:20px";
  const card = document.createElement("div");
  card.style.cssText =
    "background:var(--surface-strong,#fff);border:1px solid var(--border,#e2e8f0);border-radius:14px;" +
    "padding:22px 24px;display:flex;flex-direction:column;gap:12px;align-items:center;min-width:260px";
  const tip = document.createElement("div");
  tip.textContent = "🛡️ 请完成人机验证";
  tip.style.cssText = "font-size:14px;color:var(--text,#1e293b);font-weight:600";
  const slot = document.createElement("div");
  const switchLink = document.createElement("button");
  switchLink.style.cssText =
    "font:inherit;font-size:12.5px;color:var(--accent,#2563eb);background:none;border:none;cursor:pointer;" +
    (showSwitch ? "" : "display:none");
  const setSwitchLabel = (text: string) => {
    switchLink.textContent = `${text} ›`;
  };
  const cancel = document.createElement("button");
  cancel.textContent = "取消";
  cancel.style.cssText =
    "font:inherit;font-size:13px;color:var(--muted,#64748b);background:none;border:1px solid var(--border,#e2e8f0);" +
    "border-radius:8px;padding:5px 18px;cursor:pointer";
  card.append(tip, slot, switchLink, cancel);
  overlay.appendChild(card);
  document.body.appendChild(overlay);
  return { overlay, slot, cancel, switchLink, setSwitchLabel };
}
