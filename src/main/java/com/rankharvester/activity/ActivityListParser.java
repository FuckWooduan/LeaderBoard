package com.rankharvester.activity;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * activityList XML → 活动行解析器。
 *
 * <p>解密后的 activityList 是 XML：逐个 {@code <child>} 节点，含直接子元素
 * {@code <id> <activity> <btnIndex> <url> <description>} 与 {@code <condition startTime endTime>}。
 * 与 StrikeGod 引擎 {@code ActivityFetchService.parseChildElement} 对齐。
 *
 * <p>{@code <id>} 为去重键，缺失则跳过该节点。禁用外部实体（防 XXE）。
 */
@Component
public class ActivityListParser {

    private static final Logger log = LoggerFactory.getLogger(ActivityListParser.class);

    /** 解析 activityList XML 字符串为活动行。 */
    public List<ActivityRow> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        Document doc;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            safe(dbf);
            doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            log.warn("[活动] activityList XML 解析失败: {}", e.getMessage());
            return List.of();
        }
        NodeList children = doc.getElementsByTagName("child");
        var out = new ArrayList<ActivityRow>(children.getLength());
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            String idText = childText(child, "id");
            Long id = parseLong(idText);
            if (id == null) {
                continue; // 无 id 无法去重
            }
            Element cond = firstChildElement(child, "condition");
            out.add(new ActivityRow(
                    id,
                    childText(child, "activity"),
                    childText(child, "btnIndex"),
                    childText(child, "description"),
                    childText(child, "url"),
                    cond == null ? null : attrOrNull(cond, "startTime"),
                    cond == null ? null : attrOrNull(cond, "endTime")));
        }
        if (out.isEmpty() && children.getLength() == 0 && log.isDebugEnabled()) {
            log.debug("[活动] XML 无 <child> 节点，根元素={}",
                    doc.getDocumentElement() == null ? "null" : doc.getDocumentElement().getTagName());
        }
        return out;
    }

    /** 取某节点下第一个指定标签的直接子元素的文本（trim，缺失返回 null）。 */
    private static String childText(Element parent, String tag) {
        Element e = firstChildElement(parent, tag);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }

    /** 取第一个指定标签的直接子元素。 */
    private static Element firstChildElement(Element parent, String tag) {
        NodeList ns = parent.getChildNodes();
        for (int i = 0; i < ns.getLength(); i++) {
            Node n = ns.item(i);
            if (n instanceof Element el && tag.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    private static String attrOrNull(Element e, String name) {
        String v = e.getAttribute(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 关闭 DTD / 外部实体，避免 XXE；不支持的特性忽略。 */
    private static void safe(DocumentBuilderFactory dbf) {
        for (String f : new String[]{
                "http://apache.org/xml/features/disallow-doctype-decl",
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities"}) {
            try {
                dbf.setFeature(f, f.endsWith("disallow-doctype-decl"));
            } catch (Exception ignore) {
                // 该实现不支持此特性，忽略
            }
        }
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
    }
}
