package com.rankharvester.rank.fetch.parse;

import static com.rankharvester.apc.ApcValues.asInt;
import static com.rankharvester.apc.ApcValues.asList;
import static com.rankharvester.apc.ApcValues.asLong;
import static com.rankharvester.apc.ApcValues.asMap;
import static com.rankharvester.apc.ApcValues.asStr;

import com.rankharvester.rank.model.rows.TeamRankRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 战队榜（Team）回包解析器。
 *
 * <p>输入为 {@code onGetTeamList} 的 parameters：
 * {@code [ [bean.ClientTeamListInfoBean...], totalCount, pageMeta ]}。
 * 战队榜<b>有限且回包带总数</b>（{@code parameters[1]}），这是它区别于战力/经验/天梯榜的关键：
 * 翻页可用「offset+本页行数 ≥ 总数」精确终止。{@code rank = offset + 下标 + 1}（回包无显式名次）。
 */
@Component
public class TeamRankParser {

    /** 一页解析结果：结构化行 + 服务端总数（缺失为 null）。 */
    public record Page(List<TeamRankRow> rows, Integer total) {}

    public Page parse(List<Object> parameters, int offset) {
        if (parameters == null || parameters.isEmpty()) {
            return new Page(List.of(), null);
        }
        List<?> teams = asList(parameters.get(0));
        if (teams == null) {
            return new Page(List.of(), null);
        }

        var out = new ArrayList<TeamRankRow>(teams.size());
        int idx = 0;
        for (Object o : teams) {
            Map<String, Object> bean = asMap(o);
            if (bean == null) {
                idx++;
                continue;
            }
            Long teamId = asLong(bean.get("teamId"));
            if (teamId == null) {
                idx++;
                continue;
            }
            int rank = offset + idx + 1;
            out.add(new TeamRankRow(
                    rank,
                    teamId,
                    asStr(bean.get("name")),
                    asStr(bean.get("leaderName")),
                    asInt(bean.get("teamLv")),
                    asLong(bean.get("teamExp")),
                    asInt(bean.get("mNumber")),
                    asInt(bean.get("mNumberLimit")),
                    asStr(bean.get("declaration")),
                    asInt(bean.get("rpgTeamCapacity")),
                    asInt(bean.get("rpgTeamLv")),
                    asInt(bean.get("rpgTeamNumber")),
                    unsigned32(asLong(bean.get("rpgTeamTotalBonus"))),
                    asStr(bean.get("rpgTeamDeclaration"))));
            idx++;
        }

        Integer total = parameters.size() >= 2 ? asInt(parameters.get(1)) : null;
        if (total != null && total < 0) {
            total = null;
        }
        return new Page(out, total);
    }

    /** 冒险贡献：游戏端用 signed int32 存，超过 2³¹ 会溢出成负数；按 unsigned32 还原真实值（负数 +2³²）。 */
    private static Long unsigned32(Long v) {
        if (v != null && v < 0) {
            return v + 4294967296L;
        }
        return v;
    }
}
