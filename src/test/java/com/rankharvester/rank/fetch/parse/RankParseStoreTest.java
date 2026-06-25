package com.rankharvester.rank.fetch.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.rankharvester.rank.model.rows.LadderRankRow;
import com.rankharvester.rank.model.rows.ReducedRankRow;
import com.rankharvester.rank.store.LadderRankStore;
import com.rankharvester.rank.store.RankTableSchema;
import com.rankharvester.rank.store.ReducedRankStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用真实回包样本（/Users/birditch/Downloads/排行榜/*.txt 内联）驱动：解析 → 落地 → 读回校验。
 */
class RankParseStoreTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    // ===== 真实回包：全区经验排行榜（rankType 331）callbackGetReducedRankByPage =====
    private static final String REDUCED_CALLBACK =
            """
            {
              "__AMF_CLASS__": "common.net.APC",
              "functionName": "callbackGetReducedRankByPage",
              "parameters": [
                1, 331, 0, 10000,
                [
                  {
                    "__AMF_CLASS__": "bean.RankBean",
                    "lastRank": 1, "dateline": 1777875797050, "updateDisplayCount": 0,
                    "rankId": 0, "display": true, "uniqueIdBean": 1005201588581,
                    "infoBean": {
                      "__AMF_CLASS__": "bean.ClientRpgDataRankBean",
                      "teamName": "梦曦", "blueVipType": 0, "loginId": 52, "douwaVip": 0,
                      "homelandOpen": true, "charId": 1588581, "lv": 677, "charName": "永恒唯一",
                      "operatorId": 1, "blueVipLevel": 0
                    },
                    "lastChangeDisplayFlag": 0,
                    "scoreBean": {
                      "__AMF_CLASS__": "bean.RpgDataScore",
                      "allNumber": "13", "number": "12651426420", "dateline": 1777875797044,
                      "gcId": 1005201588581, "rebirth": 0
                    }
                  }
                ],
                10000
              ]
            }
            """;

    // ===== 真实回包：天梯联赛排行榜 callbackGetRankNew =====
    private static final String LADDER_CALLBACK =
            """
            {
              "__AMF_CLASS__": "common.net.APC",
              "functionName": "callbackGetRankNew",
              "parameters": [
                23, [4], 3, 0, 0,
                [
                  {
                    "__AMF_CLASS__": "bean.ClientRankKingBean",
                    "teamName": "爱情", "lastRank": 0, "medalIndex": 0, "loginId": 6,
                    "charId": 25740730, "lv": 150, "userName": "1512573279", "charName": "总在爱里心碎",
                    "extraValue": 0, "medalLine": 0, "curRank": 1, "number": 5039,
                    "teamId": 152921, "operatorId": 0
                  }
                ]
              ]
            }
            """;

    @SuppressWarnings("unchecked")
    private static List<Object> parameters(String callbackJson) {
        Map<String, Object> root = MAPPER.readValue(callbackJson, Map.class);
        return (List<Object>) root.get("parameters");
    }

    @Test
    void reduced_parses_packet_fields_exactly() {
        List<ReducedRankRow> rows = new ReducedRankParser().parse(parameters(REDUCED_CALLBACK));
        assertThat(rows).hasSize(1);
        ReducedRankRow r = rows.getFirst();
        assertThat(r.rank()).isEqualTo(1);
        assertThat(r.rankType()).isEqualTo(331);
        assertThat(r.lastRank()).isEqualTo(1L);
        assertThat(r.dateline()).isEqualTo(1777875797050L);
        assertThat(r.display()).isTrue();
        assertThat(r.uniqueIdBean()).isEqualTo(1005201588581L);
        assertThat(r.infoTeamName()).isEqualTo("梦曦");
        assertThat(r.infoLoginId()).isEqualTo(52L);
        assertThat(r.infoHomelandOpen()).isTrue();
        assertThat(r.infoCharId()).isEqualTo(1588581L);
        assertThat(r.infoLv()).isEqualTo(677);
        assertThat(r.infoCharName()).isEqualTo("永恒唯一");
        assertThat(r.infoOperatorId()).isEqualTo(1);
        assertThat(r.scoreAllNumber()).isEqualTo(13L);
        assertThat(r.scoreNumber()).isEqualTo(12651426420L); // 回包是字符串 "12651426420"
        assertThat(r.scoreDateline()).isEqualTo(1777875797044L);
        assertThat(r.scoreGcId()).isEqualTo(1005201588581L);
        assertThat(r.scoreRebirth()).isEqualTo(0);
    }

    @Test
    void ladder_parses_packet_fields_exactly() {
        List<LadderRankRow> rows = new LadderRankParser().parse(parameters(LADDER_CALLBACK));
        assertThat(rows).hasSize(1);
        LadderRankRow r = rows.getFirst();
        assertThat(r.rank()).isEqualTo(1); // curRank
        assertThat(r.teamName()).isEqualTo("爱情");
        assertThat(r.loginId()).isEqualTo(6L);
        assertThat(r.charId()).isEqualTo(25740730L);
        assertThat(r.lv()).isEqualTo(150);
        assertThat(r.userName()).isEqualTo("1512573279");
        assertThat(r.charName()).isEqualTo("总在爱里心碎");
        assertThat(r.curRank()).isEqualTo(1);
        assertThat(r.number()).isEqualTo(5039L);
        assertThat(r.teamId()).isEqualTo(152921L);
        assertThat(r.operatorId()).isEqualTo(0);
    }

    @Test
    void reduced_roundtrips_through_duckdb() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            new RankTableSchema(conn).init();
            var store = new ReducedRankStore(conn);
            var rows = new ReducedRankParser().parse(parameters(REDUCED_CALLBACK));

            store.appendPage("EXP:331:s1", 1, 999L, 1777875797000L, rows);
            assertThat(store.count("EXP:331:s1")).isEqualTo(1);

            try (var st = conn.createStatement();
                    var rs = st.executeQuery(
                            "SELECT score_number, score_gc_id, info_char_name, display FROM reduced_rank")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("score_number")).isEqualTo(12651426420L);
                assertThat(rs.getLong("score_gc_id")).isEqualTo(1005201588581L);
                assertThat(rs.getString("info_char_name")).isEqualTo("永恒唯一");
                assertThat(rs.getBoolean("display")).isTrue();
            }

            // 再 append 同一玩家（同 gcId，新快照）：物理两行但 count 按 gcId 去重仍为 1
            store.appendPage("EXP:331:s1", 1, 1000L, 1777875798000L, rows);
            assertThat(store.count("EXP:331:s1")).isEqualTo(1);
        }
    }

    @Test
    void ladder_roundtrips_through_duckdb() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            new RankTableSchema(conn).init();
            var store = new LadderRankStore(conn);
            var rows = new LadderRankParser().parse(parameters(LADDER_CALLBACK));

            store.writeLatest("LADDER:s1", 1, 777L, 1777875797000L, rows);
            assertThat(store.count("LADDER:s1")).isEqualTo(1);

            try (var st = conn.createStatement();
                    var rs = st.executeQuery("SELECT number, char_id, team_id, user_name FROM ladder_rank")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("number")).isEqualTo(5039L);
                assertThat(rs.getLong("char_id")).isEqualTo(25740730L);
                assertThat(rs.getLong("team_id")).isEqualTo(152921L);
                assertThat(rs.getString("user_name")).isEqualTo("1512573279");
            }
        }
    }
}
