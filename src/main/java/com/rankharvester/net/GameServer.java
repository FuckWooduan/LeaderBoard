package com.rankharvester.net;

import java.util.Arrays;
import java.util.Optional;

/**
 * 游戏服务器枚举（移植自 StrikeGod.Engine）。
 *
 * <p>{@code id} 用于游戏登录路径中的服号识别（{@code play-sid-{id}}、flashvars serverId）；
 * {@code tcpAddress} 是真实游戏服 host:port；{@code districtId} 是排行榜侧的区服标识。
 */
public enum GameServer {
    TELECOM_8(15, "电信八区", "tc-sh-138.4399ssjj3.com:2000", 24),
    TELECOM_7(13, "电信七区", "tc-sh-230.4399ssjj3.com:2000", 22),
    TELECOM_6(11, "电信六区", "tc-sh-030.4399ssjj3.com:2000", 17),
    TELECOM_5(9, "电信五区", "tc-sh-213.4399ssjj3.com:2000", 14),
    TELECOM_4(7, "电信四区", "tc-bj-127.4399ssjj3.com:2000", 12),
    TELECOM_3(6, "电信三区", "tc-gz-085.4399ssjj3.com:2000", 10),
    TELECOM_2(4, "电信二区", "tc-gz-046.4399ssjj3.com:2000", 8),
    TELECOM_1(2, "电信一区", "s2d.4399ssjj3.com:2010", 6),

    UNICOM_6(16, "联通六区", "tc-sh-095.4399ssjj3.com:2000", 28),
    UNICOM_5(14, "联通五区", "tc-bj-248.4399ssjj3.com:2000", 18),
    UNICOM_4(10, "联通四区", "tc-bj-055.4399ssjj3.com:2000", 16),
    UNICOM_3(8, "联通三区", "tc-gz-177.4399ssjj3.com:2000", 13),
    UNICOM_2(5, "联通二区", "tc-gz-076.4399ssjj3.com:2000", 9),
    UNICOM_1(3, "联通一区", "tc-gz-030.4399ssjj3.com:2000", 7),

    DUAL_7(21, "双线七区", "tc-sh-212.4399ssjj3.com:2000", 52),
    DUAL_6(20, "双线六区", "tc-bj-237.ssjj.abd007.com:2000", 46),
    DUAL_5(19, "双线五区", "tc-bj-032.4399ssjj.com:2000", 45),
    DUAL_4(18, "双线四区", "tc-bj-153.4399ssjj3.com:2000", 42),
    DUAL_3(17, "双线三区", "tc-bj-236.4399ssjj3.com:2000", 31),
    DUAL_2(12, "双线二区", "tc-gz-211.4399ssjj3.com:2000", 19),
    DUAL_1(1, "双线一区", "s1d.4399ssjj3.com:2000", 5);

    private final int id;
    private final String serverName;
    private final String tcpAddress;
    private final int districtId;

    GameServer(int id, String serverName, String tcpAddress, int districtId) {
        this.id = id;
        this.serverName = serverName;
        this.tcpAddress = tcpAddress;
        this.districtId = districtId;
    }

    public int id() {
        return id;
    }

    public String serverName() {
        return serverName;
    }

    public String tcpAddress() {
        return tcpAddress;
    }

    public int districtId() {
        return districtId;
    }

    /** host:port → host。 */
    public String host() {
        int i = tcpAddress.lastIndexOf(':');
        return i < 0 ? tcpAddress : tcpAddress.substring(0, i);
    }

    /** host:port → port。 */
    public int port() {
        int i = tcpAddress.lastIndexOf(':');
        return i < 0 ? 0 : Integer.parseInt(tcpAddress.substring(i + 1));
    }

    public static Optional<GameServer> fromId(int id) {
        return Arrays.stream(values()).filter(s -> s.id == id).findFirst();
    }

    public static GameServer fromIdOrThrow(int id) {
        return fromId(id).orElseThrow(() -> new IllegalArgumentException("未知的服务器ID: " + id));
    }

    /** 按 districtId（游戏回包/对外服号）查找。 */
    public static Optional<GameServer> fromDistrictId(int districtId) {
        return Arrays.stream(values()).filter(s -> s.districtId == districtId).findFirst();
    }

    /** 登录服号字符串（如 "15"）→ 服务器名（"电信八区"）；未知则回退原串。 */
    public static String nameOfId(String id) {
        try {
            return fromId(Integer.parseInt(id.trim())).map(GameServer::serverName).orElse(id);
        } catch (NumberFormatException e) {
            return id;
        }
    }

    /** 登录 id 字符串 → districtId 字符串（对外服号）；未知返回 null。 */
    public static String districtOfLoginId(String loginId) {
        try {
            return fromId(Integer.parseInt(loginId.trim())).map(s -> String.valueOf(s.districtId)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** districtId 字符串（对外服号）→ 登录 id 字符串；未知返回 null。 */
    public static String loginIdOfDistrict(String districtId) {
        try {
            return fromDistrictId(Integer.parseInt(districtId.trim())).map(s -> String.valueOf(s.id)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** districtId（对外服号）→ 服务器名；未知回退 "N区"。 */
    public static String nameOfDistrict(int districtId) {
        return fromDistrictId(districtId).map(GameServer::serverName).orElse(districtId + "区");
    }
}
