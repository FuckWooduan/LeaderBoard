package com.rankharvester.rank.fetch.parse;

import static com.rankharvester.apc.ApcValues.asInt;
import static com.rankharvester.apc.ApcValues.asIntOr;
import static com.rankharvester.apc.ApcValues.asList;
import static com.rankharvester.apc.ApcValues.asLong;
import static com.rankharvester.apc.ApcValues.asMap;
import static com.rankharvester.apc.ApcValues.asStr;

import com.rankharvester.rank.model.rows.LadderRankRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 天梯联赛榜（Ladder）回包解析器。
 *
 * <p>输入为 {@code callbackGetRankNew} 的 parameters：
 * {@code [rankId, category[], meta, offset, meta2, [bean.ClientRankKingBean...]]}。
 * 行集在 parameters[5]。{@code rank = curRank>0 ? curRank : offset + 下标 + 1}。
 */
@Component
public class LadderRankParser {

    private static final int IDX_OFFSET = 3;
    private static final int IDX_ROWS = 5;

    public List<LadderRankRow> parse(List<Object> parameters) {
        if (parameters == null || parameters.size() <= IDX_ROWS) {
            return List.of();
        }
        int offset = asIntOr(parameters.get(IDX_OFFSET), 0);
        List<?> rows = asList(parameters.get(IDX_ROWS));
        if (rows == null) {
            return List.of();
        }

        var out = new ArrayList<LadderRankRow>(rows.size());
        int idx = 0;
        for (Object o : rows) {
            Map<String, Object> bean = asMap(o);
            if (bean == null) {
                idx++;
                continue;
            }
            Integer curRank = asInt(bean.get("curRank"));
            int rank = (curRank != null && curRank > 0) ? curRank : offset + idx + 1;
            out.add(new LadderRankRow(
                    rank,
                    asStr(bean.get("teamName")),
                    asInt(bean.get("lastRank")),
                    asInt(bean.get("medalIndex")),
                    asLong(bean.get("loginId")),
                    asLong(bean.get("charId")),
                    asInt(bean.get("lv")),
                    asStr(bean.get("userName")),
                    asStr(bean.get("charName")),
                    asLong(bean.get("extraValue")),
                    asInt(bean.get("medalLine")),
                    curRank,
                    asLong(bean.get("number")),
                    asLong(bean.get("teamId")),
                    asInt(bean.get("operatorId"))));
            idx++;
        }
        return out;
    }
}
