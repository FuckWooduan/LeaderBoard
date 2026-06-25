package com.rankharvester.rank.fetch.parse;

import static com.rankharvester.apc.ApcValues.asBool;
import static com.rankharvester.apc.ApcValues.asInt;
import static com.rankharvester.apc.ApcValues.asIntOr;
import static com.rankharvester.apc.ApcValues.asList;
import static com.rankharvester.apc.ApcValues.asLong;
import static com.rankharvester.apc.ApcValues.asMap;
import static com.rankharvester.apc.ApcValues.asStr;
import static com.rankharvester.apc.ApcValues.get;

import com.rankharvester.rank.model.rows.ReducedRankRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 战力/经验榜（Reduced）回包解析器。
 *
 * <p>输入为 {@code callbackGetReducedRankByPage} 的 parameters：
 * {@code [status, rankType, pageIndex, 本页行数, [bean.RankBean...], maxCount]}。
 * 注意：请求 {@code getReducedRankByPage(rankType, pageIndex, pageSize, 0)} 的第 2 个参数是
 * <b>页码（0,1,2…）</b>而非行偏移；回包 {@code parameters[5]} 是排行榜<b>最大可显示条数（cap，如 1000）</b>，
 * 实际条数可能更少（最后一页短页/空页为准）。逐行取出 {@code RankBean} + 嵌套 {@code infoBean}/{@code scoreBean}
 * 拍平为 {@link ReducedRankRow}；{@code rank = rankBase + 下标 + 1}（rankBase = pageIndex × pageSize，由调用方传入）。
 */
@Component
public class ReducedRankParser {

    private static final int IDX_RANK_TYPE = 1;
    private static final int IDX_ROWS = 4;
    private static final int IDX_MAX = 5;

    /** 一页解析结果：结构化行 + 排行榜最大条数 cap（{@code parameters[5]}，缺失为 null）。 */
    public record Page(List<ReducedRankRow> rows, Integer total) {}

    /** 向后兼容：按 rankBase=0 解析，仅取行（用于单测/非分页场景）。 */
    public List<ReducedRankRow> parse(List<Object> parameters) {
        return parsePage(parameters, 0).rows();
    }

    /**
     * 解析一页。
     *
     * @param rankBase 本页首行的「前序行数」（= pageIndex × pageSize），rank = rankBase + 下标 + 1
     * @return 行 + 最大条数 cap
     */
    public Page parsePage(List<Object> parameters, int rankBase) {
        if (parameters == null || parameters.size() <= IDX_ROWS) {
            return new Page(List.of(), null);
        }
        int rankType = asIntOr(parameters.get(IDX_RANK_TYPE), 0);
        List<?> rows = asList(parameters.get(IDX_ROWS));
        if (rows == null) {
            return new Page(List.of(), null);
        }
        Integer total = parameters.size() > IDX_MAX ? asInt(parameters.get(IDX_MAX)) : null;
        if (total != null && total < 0) {
            total = null;
        }

        var out = new ArrayList<ReducedRankRow>(rows.size());
        int idx = 0;
        for (Object o : rows) {
            Map<String, Object> bean = asMap(o);
            if (bean == null) {
                idx++;
                continue;
            }
            Map<String, Object> info = asMap(bean.get("infoBean"));
            Map<String, Object> score = asMap(bean.get("scoreBean"));
            int rank = rankBase + idx + 1;
            out.add(new ReducedRankRow(
                    rank,
                    rankType,
                    asLong(bean.get("lastRank")),
                    asLong(bean.get("dateline")),
                    asInt(bean.get("updateDisplayCount")),
                    asLong(bean.get("rankId")),
                    asBool(bean.get("display")),
                    asLong(bean.get("uniqueIdBean")),
                    asLong(bean.get("lastChangeDisplayFlag")),
                    asStr(get(info, "teamName")),
                    asInt(get(info, "blueVipType")),
                    asLong(get(info, "loginId")),
                    asInt(get(info, "douwaVip")),
                    asBool(get(info, "homelandOpen")),
                    asLong(get(info, "charId")),
                    asInt(get(info, "lv")),
                    asStr(get(info, "charName")),
                    asInt(get(info, "operatorId")),
                    asInt(get(info, "blueVipLevel")),
                    asLong(get(score, "allNumber")),
                    asLong(get(score, "number")),
                    asLong(get(score, "dateline")),
                    asLong(get(score, "gcId")),
                    asInt(get(score, "rebirth"))));
            idx++;
        }
        return new Page(out, total);
    }
}
