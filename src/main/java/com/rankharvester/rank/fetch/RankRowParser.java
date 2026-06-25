package com.rankharvester.rank.fetch;

import com.rankharvester.apc.ApcObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 回包 → 榜行解析器。
 *
 * <p>排行榜回包的载荷是 AMF3 解出的「Map 列表」（每行一个 Map），JSON 解析开销大，
 * 因此解析在抓取线程内完成、只保留结构化字段 + 原始 extraJson。
 *
 * <p>兼容 ReducedRank（{@code [status,rankType,offset,pageSize,rows,total]}）与 TeamList 等形状：
 * 启发式地取「元素为 Map 的那个 List 参数」作为行集，字段名做多别名兼容。
 */
@Component
public class RankRowParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final List<String> ID_KEYS = List.of("gcid", "gcId", "id", "uid", "teamId", "subjectId");
    private static final List<String> NAME_KEYS = List.of("playerName", "name", "teamName", "nickName", "nick");
    private static final List<String> SCORE_KEYS = List.of("power", "score", "exp", "value", "point");

    /** 解析单页回包为榜行；找不到行集时返回空。 */
    public List<com.rankharvester.rank.model.RankRow> parse(ApcObject apc) {
        var rowList = locateRowList(apc.getParameters());
        if (rowList == null) {
            return List.of();
        }
        var out = new ArrayList<com.rankharvester.rank.model.RankRow>(rowList.size());
        int fallbackRank = 1;
        for (Object o : rowList) {
            if (!(o instanceof Map<?, ?> raw)) {
                fallbackRank++;
                continue;
            }
            @SuppressWarnings("unchecked")
            var row = (Map<String, Object>) raw;
            int rank = intOf(row.get("rank"), fallbackRank);
            String subjectId = strOf(firstPresent(row, ID_KEYS), "");
            String name = strOf(firstPresent(row, NAME_KEYS), "");
            long score = longOf(firstPresent(row, SCORE_KEYS), 0L);
            out.add(new com.rankharvester.rank.model.RankRow(rank, subjectId, name, score, toJson(row)));
            fallbackRank++;
        }
        return out;
    }

    /** 在参数列表中找到「元素为 Map 的 List」作为行集。 */
    private List<?> locateRowList(List<Object> params) {
        if (params == null) return null;
        for (Object p : params) {
            if (p instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?>) {
                return list;
            }
        }
        return null;
    }

    private static Object firstPresent(Map<String, Object> row, List<String> keys) {
        for (String k : keys) {
            if (row.containsKey(k) && row.get(k) != null) {
                return row.get(k);
            }
        }
        return null;
    }

    private static int intOf(Object v, int dft) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return dft;
    }

    private static long longOf(Object v, long dft) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return dft;
    }

    private static String strOf(Object v, String dft) {
        return v == null ? dft : v.toString();
    }

    private static String toJson(Map<String, Object> row) {
        try {
            return MAPPER.writeValueAsString(row);
        } catch (JacksonException e) {
            return "{}";
        }
    }
}
