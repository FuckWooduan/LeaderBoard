package com.rankharvester.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 辅助预测（OpenAI 兼容接口，默认 gemini-3-pro）。
 *
 * <p>当前用于「隐藏赛季榜」的隐藏玩家身份预测：给定一批隐藏槽位（只知 last_rank=上赛季名次，按 last_rank 升序）
 * 和一份<b>确定的候选名单</b>（同分区组的公开榜里、减去已可见玩家后的玩家，含战力/VIP/境界/同组榜分数），
 * 让模型把每个隐藏槽位匹配到最可能的候选人。结果一律由调用方标「预测」。
 */
@Service
public class AiPredictor {

    private static final Logger log = LoggerFactory.getLogger(AiPredictor.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AiPredictor(
            @Value("${rankharvester.ai.baseurl:}") String baseUrl,
            @Value("${rankharvester.ai.apikey:}") String apiKey,
            @Value("${rankharvester.ai.model:gemini-3-pro}") String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = (model == null || model.isBlank()) ? "gemini-3-pro" : model.trim();
        this.enabled = !this.baseUrl.isBlank() && !this.apiKey.isBlank();
        log.info("AI 预测 {}（model={}）", enabled ? "已启用" : "未启用", this.model);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 隐藏槽位：当前名次 rank + 上赛季名次 lastRank（可能为 null）+ 沿 last_rank「同分区逐赛季回溯」得到的实名轨迹
     * trajectory（新→旧，过去赛季公开有名；出现实名不停、挖满若干季）。trajectory 是判定该槽位身份的强证据。
     */
    public record Slot(int rank, Long lastRank, List<TrajPoint> trajectory) {}

    /** 轨迹上的一季：该季同分区名次 rank + 该名次上的玩家名 name（过去赛季公开，可能为 null=那季也隐藏/缺）。 */
    public record TrajPoint(int rank, String name) {}

    /** 一个赛季的成绩：名次 rank + 分数 score（缺席的赛季根本不会出现在 history 里）。 */
    public record HistPoint(Integer rank, Long score) {}

    /**
     * 候选人：名字 + 特征 + 逐赛季历史 history（新→旧，只含他确实打了的赛季；每项含名次+分数）
     * + 近期动量 expDelta/lvDelta（经验/等级变化量，活跃度信号；hours=该变化跨的小时数；无则 null）。
     */
    public record Candidate(String name, Long power, Long vip, Long realm, List<HistPoint> history,
            Long expDelta, Long lvDelta, Long momentumHours) {}

    /** 可见玩家锚点：名次 rank → 真实分数 score（给分数预测当标尺，保证单调）。 */
    public record Anchor(int rank, long score) {}

    /** 预测结果：rank 匹配到 name；brawl 榜还含预测分数 score 及其置信度。 */
    public record Prediction(int rank, String name, double confidence, Long score, double scoreConfidence,
            String reason) {}

    /**
     * 让模型把隐藏槽位匹配到候选人（brawl=true 时额外预测分数）。AI 不可用 / 异常 → 返回空（调用方降级）。
     */
    public List<Prediction> predictHidden(String boardName, int partition, List<Slot> hiddenSlots,
            List<Candidate> candidates, List<Anchor> anchors, boolean brawl, java.util.Map<Integer, String> known) {
        var out = new ArrayList<Prediction>();
        if (!enabled || hiddenSlots.isEmpty() || candidates.isEmpty()) {
            return out;
        }
        try {
            String content = chat(buildSystem(brawl),
                    buildUser(boardName, partition, hiddenSlots, candidates, anchors, known));
            if (content == null) {
                return out;
            }
            JsonNode arr = parseArray(content);
            if (arr == null) {
                return out;
            }
            for (JsonNode n : arr) {
                int rank = n.path("rank").asInt(0);
                String name = n.path("name").asString("");
                double conf = n.path("confidence").asDouble(0.0);
                String reason = n.path("reason").asString("");
                Long score = null;
                double sconf = 0.0;
                if (brawl && n.has("score") && !n.path("score").isNull()) {
                    score = n.path("score").asLong();
                    sconf = n.path("scoreConfidence").asDouble(conf);
                }
                if (rank > 0 && !name.isBlank()) {
                    out.add(new Prediction(rank, name, Math.max(0, Math.min(1, conf)),
                            score, Math.max(0, Math.min(1, sconf)), reason));
                }
            }
        } catch (Exception e) {
            log.warn("AI 隐藏预测异常，降级: {}", e.getMessage());
        }
        return out;
    }

    private static String buildSystem(boolean brawl) {
        String base = "你是排行榜数据分析助手。这是某游戏「当前赛季隐藏榜」的一个分区。"
                + "hiddenSlots 是本季被隐藏的名次槽位（按当前名次升序，可能带 lastRank=该玩家上赛季名次，没打则无）。"
                + "每个槽位还可能带 pastSeasons=『沿 last_rank 同分区逐赛季回溯』得到的轨迹（新→旧）：每项 {rank=那季同分区名次, name=那季该名次上的玩家名}。"
                + "过去赛季是公开的，所以 pastSeasons 里的 name 就是该槽位玩家在那一季的真实名字——这是判定身份的『最强证据』："
                + "若某个 name 在 pastSeasons 里反复出现/最近一季就有，基本可直接断定该槽位就是这个人（优先采用，给高 confidence）；"
                + "pastSeasons 为空或 name 多为 null（那几季也隐藏）时，再退回用 candidates 的战力/历史趋势综合判断。"
                + "candidates 是『本分区真正隐藏的那批玩家的完整名单』——隐藏槽位和候选是一一对应的（每个候选恰好对应一个槽位）。"
                + "每个候选含：战力 power、VIP vip、境界 realm，以及逐赛季历史 history（新→旧，只列他确实打了的赛季，每项有该季名次 rank 和分数 score）。"
                + "请把每个隐藏槽位匹配到最可能的候选人。判断要点（不要只看名次）："
                + "1) 分数才是『是否真打了』的信号：history 里某季 score 只有几十万~一百万左右=那季基本只吃保底奖励、没认真打；几千万=那季真打了。"
                + "2) 不是每个人每季都打乱斗，history 会缺某些赛季；要看他『打了的那些赛季』的名次与分数趋势，综合判断他本季的强弱与名次。"
                + "3) 结合 lastRank、历史名次轨迹、战力/VIP/境界 一起排，越强的人对应越靠前的当前名次。"
                + "3.5) 【活跃度/动量是关键，别只看静态战力】candidates 里的 expDelta=该候选近期经验增量、lvDelta=近期升级数"
                + "（momentumHours=该变化跨的小时数）：经验近期暴涨（瞬时涨约 2800 万往往是开了 3000 狮子头满体力在认真打）、"
                + "或近期连升 2~3 级，说明他这阵子在活跃打榜/打乱斗，本季很可能在认真打，应排到更靠前、分数也偏高；"
                + "经验/等级长期几乎不动（delta≈0 或缺失）说明可能没怎么打，哪怕战力/VIP 高也别盲目排前面、分数别给高。"
                + "活跃度优先于静态战力——一个战力一般但近期狂涨经验的人，往往比战力高但毫无动静的人本季排得更前。"
                + "4) candidates 是完整集合，每个候选恰好用一次，尽量把所有槽位都匹配上。"
                + "knownIdentities 里给出的槽位身份『已确定、不要改』，这些候选也不要再分给别的槽位。";
        if (brawl) {
            base += "这是『乱斗榜』，请同时预测每个隐藏槽位的『本赛季分数』score(整数)。"
                    + "分数量级以该候选 history 里『真打了的赛季』(几千万级那种)为主要依据，顺其趋势外推；"
                    + "他若近几季都是几千万，本季也应是几千万级，别压低。"
                    + "visibleAnchors 是同分区可见的中下游弱号(名次,分数)，只作『名次越靠前分数不低于后面可见玩家』的下限，不能当上限。"
                    + "保证名次越靠前分数越高。每项额外给 score(整数) 和 scoreConfidence(0到1)。";
        }
        base += "只输出 JSON 数组，不要任何解释文字，每项形如 {\"rank\":整数, \"name\":\"候选名\", \"confidence\":0到1的小数"
                + (brawl ? ", \"score\":整数, \"scoreConfidence\":0到1的小数" : "")
                + ", \"reason\":\"简短理由\"}。拿不准给低 confidence。";
        return base;
    }

    private String buildUser(String boardName, int partition, List<Slot> slots, List<Candidate> cands,
            List<Anchor> anchors, java.util.Map<Integer, String> known) {
        ObjectNode root = JSON.createObjectNode();
        root.put("board", boardName);
        root.put("partition", partition + 1);
        ArrayNode hs = root.putArray("hiddenSlots");
        for (Slot s : slots) {
            ObjectNode o = hs.addObject();
            o.put("rank", s.rank());
            if (s.lastRank() != null) {
                o.put("lastRank", s.lastRank());
            } else {
                o.putNull("lastRank");
            }
            // 该槽位「同分区逐赛季回溯」轨迹（新→旧）：每项 {rank=那季名次, name=那季该名次的玩家名(过去公开)}。
            if (s.trajectory() != null && !s.trajectory().isEmpty()) {
                ArrayNode tj = o.putArray("pastSeasons");
                for (TrajPoint tp : s.trajectory()) {
                    ObjectNode to = tj.addObject();
                    to.put("rank", tp.rank());
                    if (tp.name() == null) {
                        to.putNull("name");
                    } else {
                        to.put("name", tp.name());
                    }
                }
            }
        }
        ArrayNode cs = root.putArray("candidates");
        for (Candidate c : cands) {
            ObjectNode o = cs.addObject();
            o.put("name", c.name());
            putNum(o, "power", c.power());
            putNum(o, "vip", c.vip());
            putNum(o, "realm", c.realm());
            // 近期动量（活跃度信号）：expDelta=近期经验增量，lvDelta=近期升级数，momentumHours=跨的小时数。
            putNum(o, "expDelta", c.expDelta());
            putNum(o, "lvDelta", c.lvDelta());
            putNum(o, "momentumHours", c.momentumHours());
            if (c.history() != null && !c.history().isEmpty()) {
                ArrayNode h = o.putArray("history"); // 逐赛季 {rank, score}，新→旧
                for (HistPoint hp : c.history()) {
                    ObjectNode ho = h.addObject();
                    if (hp.rank() == null) {
                        ho.putNull("rank");
                    } else {
                        ho.put("rank", hp.rank());
                    }
                    if (hp.score() == null) {
                        ho.putNull("score");
                    } else {
                        ho.put("score", hp.score());
                    }
                }
            }
        }
        if (known != null && !known.isEmpty()) {
            ArrayNode kn = root.putArray("knownIdentities");
            for (var e : known.entrySet()) {
                kn.addObject().put("rank", e.getKey()).put("name", e.getValue());
            }
        }
        if (anchors != null && !anchors.isEmpty()) {
            ArrayNode an = root.putArray("visibleAnchors");
            for (Anchor a : anchors) {
                ObjectNode o = an.addObject();
                o.put("rank", a.rank());
                o.put("score", a.score());
            }
        }
        return root.toString();
    }

    private static void putNum(ObjectNode o, String k, Long v) {
        if (v == null) {
            o.putNull(k);
        } else {
            o.put(k, v);
        }
    }

    /** 调用 chat/completions，返回 message.content。 */
    private String chat(String system, String user) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        msgs.addObject().put("role", "user").put("content", user);
        var req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                // 浏览器 UA：接口前置 Cloudflare WAF 会拦非浏览器 UA（如 Java-http-client/Python-urllib → 403）
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            log.warn("AI 接口非2xx({}): {}", resp.statusCode(), trim(resp.body()));
            return null;
        }
        JsonNode node = JSON.readTree(resp.body());
        return node.path("choices").path(0).path("message").path("content").asString(null);
    }

    /** 从模型输出里抠出 JSON 数组（容忍代码块围栏 / 前后多余文字）。 */
    private static JsonNode parseArray(String content) {
        String s = content.trim();
        int lb = s.indexOf('[');
        int rb = s.lastIndexOf(']');
        if (lb < 0 || rb <= lb) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(s.substring(lb, rb + 1));
            return n.isArray() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}
