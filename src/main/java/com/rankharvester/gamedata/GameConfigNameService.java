package com.rankharvester.gamedata;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 游戏配置中文名解析：{@code sceneId → 地图名}（mapConfigXML）、{@code raceType → 玩法名}（gameModeXML）。
 *
 * <p>这两张表来自游戏的 XML 配置资源（与 gameDataBin 同为 res_config.xml 兄弟项），由
 * {@link GameDataFetcher#fetchConfigXml(String)} 下载解密。<strong>尽力而为</strong>：
 * 启动后异步加载 + 周期刷新；未就绪/拉取失败时一律回退数字 ID（如「玩法#331」「地图#101」），
 * 不阻塞「频道大厅」功能。dry-run 无 pauth 时即长期数字回退。仅房间的玩法/地图列用得到
 * （频道名、房间名服务器本就是中文）。
 */
@Service
public class GameConfigNameService {

    private static final Logger log = LoggerFactory.getLogger(GameConfigNameService.class);

    private final GameDataFetcher fetcher;
    private final com.rankharvester.account.ScoutAccountRegistry scouts;

    /** sceneId → 地图名。 */
    private volatile Map<Integer, String> mapNames = Map.of();
    /** raceType → 玩法名。 */
    private volatile Map<Integer, String> modeNames = Map.of();
    /** 玩法下拉选项（id + 中文名），按 sortIndex 排序，供前端房间筛选用。 */
    private volatile List<ModeOption> modeOptions = List.of();

    public GameConfigNameService(GameDataFetcher fetcher, com.rankharvester.account.ScoutAccountRegistry scouts) {
        this.fetcher = fetcher;
        this.scouts = scouts;
    }

    /** 玩法下拉选项。 */
    public record ModeOption(int id, String name) {}

    /** sceneId → 地图名；未命中回退「地图#id」。 */
    public String mapName(int sceneId) {
        String n = mapNames.get(sceneId);
        return n != null && !n.isBlank() ? n : "地图#" + sceneId;
    }

    /** raceType → 玩法名；未命中回退「玩法#id」。 */
    public String modeName(int raceType) {
        String n = modeNames.get(raceType);
        return n != null && !n.isBlank() ? n : "玩法#" + raceType;
    }

    /** 玩法下拉选项（可能为空——配置未就绪时前端只按数字玩法查询）。 */
    public List<ModeOption> modeOptions() {
        return modeOptions;
    }

    /** 配置是否已就绪（任一表非空）。 */
    public boolean ready() {
        return !mapNames.isEmpty() || !modeNames.isEmpty();
    }

    /**
     * 启动后异步加载 + 每 6 小时刷新（配置基本静态）。在调度线程跑，拉取慢也不影响请求线程；
     * 失败保留旧表（或空表→数字回退）。
     */
    @Scheduled(initialDelay = 20_000L, fixedDelay = 6 * 60 * 60_000L)
    void refresh() {
        try {
            String mapXml = fetchXml("mapConfigXML");
            String modeXml = fetchXml("gameModeXML");
            Map<Integer, String> maps = mapXml == null ? null : parseMapNames(mapXml);
            Map<Integer, String> modes = modeXml == null ? null : parseModeNames(modeXml);
            if (maps != null && !maps.isEmpty()) {
                mapNames = maps;
            }
            if (modes != null && !modes.isEmpty()) {
                modeNames = modes;
                modeOptions = modes.entrySet().stream()
                        .map(e -> new ModeOption(e.getKey(), e.getValue()))
                        .toList();
            }
            log.info("[GameConfig] 名称表刷新：地图 {} 条，玩法 {} 条", mapNames.size(), modeNames.size());
        } catch (Exception e) {
            log.warn("[GameConfig] 名称表刷新失败（保留旧表，回退数字 ID）: {}", e.getMessage());
        }
    }

    /** 取配置 XML：先用抓榜池的 pauth（GameDataFetcher 自带），失败再借侦察号的 pauth。 */
    private String fetchXml(String tag) {
        String xml = fetcher.fetchConfigXml(tag);
        if (xml != null) {
            return xml;
        }
        var scout = scouts.anyWithPauth();
        if (scout != null) {
            return fetcher.fetchConfigXml(scout.pauth(), scout.servers(), tag);
        }
        return null;
    }

    /** mapConfigXML：根下每个子元素一张地图，读其 {@code id} + {@code name}（属性或子元素皆可）。 */
    private static Map<Integer, String> parseMapNames(String xml) throws Exception {
        Document doc = parse(xml);
        var out = new LinkedHashMap<Integer, String>();
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) node;
            Integer id = asInt(field(el, "id"));
            String name = field(el, "name");
            if (id != null && !name.isBlank()) {
                out.putIfAbsent(id, name);
            }
        }
        return out;
    }

    /** gameModeXML：{@code <mode id=".." name=".." sortIndex="..">}，读属性；按 sortIndex 升序。 */
    private static Map<Integer, String> parseModeNames(String xml) throws Exception {
        Document doc = parse(xml);
        NodeList modes = doc.getElementsByTagName("mode");
        var byId = new LinkedHashMap<Integer, String>();
        var sortKey = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < modes.getLength(); i++) {
            Element el = (Element) modes.item(i);
            Integer id = asInt(field(el, "id"));
            String name = field(el, "name");
            if (id == null || name.isBlank()) {
                continue;
            }
            Integer sort = asInt(field(el, "sortIndex"));
            byId.putIfAbsent(id, name);
            sortKey.putIfAbsent(id, sort == null ? Integer.MAX_VALUE : sort);
        }
        var ordered = new LinkedHashMap<Integer, String>();
        byId.entrySet().stream()
                .sorted((a, b) -> Integer.compare(sortKey.get(a.getKey()), sortKey.get(b.getKey())))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }

    /** 读字段：优先属性，回退同名子元素文本；都没有返回空串。 */
    private static String field(Element el, String name) {
        String attr = el.getAttribute(name);
        if (attr != null && !attr.isBlank()) {
            return attr.trim();
        }
        NodeList ch = el.getElementsByTagName(name);
        if (ch.getLength() > 0 && ch.item(0).getTextContent() != null) {
            return ch.item(0).getTextContent().trim();
        }
        return "";
    }

    private static Integer asInt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 安全解析 XML（禁用 DTD/外部实体，防 XXE）。 */
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
