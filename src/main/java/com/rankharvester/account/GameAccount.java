package com.rankharvester.account;

import java.util.List;

/**
 * 游戏账号。一个账号可登录多个大区、读取多个排行榜/分区。
 *
 * <p>3000+ 账号由 {@code GameAccountSeeder} 从 StrikeGod 的 PostgreSQL 导出（COPY 块）
 * 灌入嵌入式 DuckDB 的 {@code game_account} 表，再由 {@link AccountRegistry} 载入内存。
 * 账号密码 / pauth / uid 仅用于登录握手与资源 HTTP，不落入快照存储，也不写日志。
 *
 * @param id        账号唯一 id（榜定义通过 gameAccountId 引用）
 * @param account   登录账号名
 * @param password  登录密码（敏感，禁止打印）
 * @param servers   该账号可用的大区列表（大区号字符串）
 * @param pauth     登录令牌（资源/活动 HTTP 用，可空）
 * @param uid       游戏 uid（可空）
 * @param enabled   是否启用该账号参与抓取
 * @param available 账号当前是否可用（探活结果）
 */
public record GameAccount(
        String id,
        String account,
        String password,
        List<String> servers,
        String pauth,
        String uid,
        boolean enabled,
        boolean available) {

    /** 向后兼容构造：无 pauth/uid 信息时，默认启用且可用。 */
    public GameAccount(String id, String account, String password, List<String> servers) {
        this(id, account, password, servers, null, null, true, true);
    }

    @Override
    public String toString() {
        return "GameAccount{id=" + id + ", account=" + mask(account)
                + ", servers=" + servers + ", enabled=" + enabled + ", available=" + available + "}";
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 2) return "**";
        return s.charAt(0) + "***" + s.charAt(s.length() - 1);
    }
}
