package com.rankharvester.rank.admin;

import com.rankharvester.rank.model.RankType;
import java.util.List;
import java.util.Map;

/**
 * 各榜型的「可展示字段」清单与默认中文显示名。
 *
 * <p>字段键 = 落地表的数据列名（不含 leaderboard_code/server_id/snapshot_id/fetched_at 等簿记列）。
 * 后台可对每个榜按字段覆盖显示名；未覆盖时用这里的默认中文名，再没有则回退字段键本身。
 */
public final class BoardFields {

    private BoardFields() {}

    private static final List<String> REDUCED = List.of(
            "rank", "rank_type", "last_rank", "dateline", "update_display_count", "rank_id", "display",
            "unique_id_bean", "last_change_display_flag", "info_team_name", "info_blue_vip_type",
            "info_login_id", "info_douwa_vip", "info_homeland_open", "info_char_id", "info_lv",
            "info_char_name", "info_operator_id", "info_blue_vip_level", "score_all_number",
            "score_number", "score_dateline", "score_gc_id", "score_rebirth");

    private static final List<String> LADDER = List.of(
            "rank", "team_name", "last_rank", "medal_index", "login_id", "char_id", "lv", "user_name",
            "char_name", "extra_value", "medal_line", "cur_rank", "number", "team_id", "operator_id");

    private static final List<String> TEAM = List.of(
            "rank", "team_id", "team_name", "leader_name", "team_lv", "team_exp", "m_number",
            "m_number_limit", "declaration", "rpg_team_capacity", "rpg_team_lv", "rpg_team_number",
            "rpg_team_total_bonus", "rpg_team_declaration");

    private static final Map<String, String> DEFAULT_LABELS = Map.ofEntries(
            Map.entry("rank", "名次"),
            Map.entry("rank_type", "榜类型"),
            Map.entry("last_rank", "上次名次"),
            Map.entry("cur_rank", "当前名次"),
            Map.entry("info_char_name", "角色名"),
            Map.entry("char_name", "角色名"),
            Map.entry("info_team_name", "战队名"),
            Map.entry("team_name", "战队名"),
            Map.entry("info_lv", "等级"),
            Map.entry("lv", "等级"),
            Map.entry("info_login_id", "登录区"),
            Map.entry("login_id", "登录区"),
            Map.entry("info_char_id", "角色ID"),
            Map.entry("char_id", "角色ID"),
            Map.entry("team_id", "战队ID"),
            Map.entry("info_operator_id", "运营商"),
            Map.entry("operator_id", "运营商"),
            Map.entry("score_number", "分值"),
            Map.entry("score_all_number", "总数"),
            Map.entry("score_rebirth", "境界"),
            Map.entry("number", "积分"),
            Map.entry("extra_value", "附加值"),
            Map.entry("user_name", "用户名"),
            Map.entry("medal_index", "勋章档"),
            Map.entry("medal_line", "勋章线"),
            Map.entry("display", "是否展示"),
            Map.entry("info_homeland_open", "家园开放"));
    // 注：战队榜的专有字段（队长/战队等级/成员数/RPG* 等）不在此硬编码默认名，
    // 一律由后台逐字段命名（未命名时前端回退字段原始键），见 BoardAdminController /api/boards/label。

    /** 某榜型的可展示字段（有序）。 */
    public static List<String> fieldsOf(RankType type) {
        return switch (type) {
            case REDUCED -> REDUCED;
            case LADDER -> LADDER;
            case TEAM -> TEAM;
            default -> List.of(); // PARTITION 暂未接入
        };
    }

    /** 字段的内置默认显示名；无则回退字段键。 */
    public static String defaultLabel(String field) {
        return DEFAULT_LABELS.getOrDefault(field, field);
    }
}
