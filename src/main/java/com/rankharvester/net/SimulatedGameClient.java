package com.rankharvester.net;

import com.rankharvester.account.GameAccount;
import com.rankharvester.apc.ApcObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模拟游戏客户端（dryRun）。
 *
 * <p>不开真实 socket，{@code call(...)} 按请求方法名返回与真实回包同构的合成数据
 * （{@code getReducedRankByPage} → RankBean 形状；{@code getRankNew} → ClientRankKingBean 形状），
 * 让「调度 → 抓取 → 解析 → 落库」全链路在没有真实游戏服时端到端可跑通、可测。
 */
public final class SimulatedGameClient implements GameClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatedGameClient.class);

    /** 每个模拟榜的总行数。 */
    private static final int SIM_TOTAL_ROWS = 250;

    @Override
    public GameConnection connectAndLogin(GameAccount account, String server) {
        log.info("[{}] 模拟登录 @ {}（dry-run，不连真实游戏服）", account.id(), server);
        return new SimulatedGameConnection(account.id(), server);
    }

    @Override
    public GameConnection connectRaw(String label, String host, int port) {
        log.info("[{}] 模拟频道服连接 @ {}:{}（dry-run）", label, host, port);
        return new SimulatedGameConnection(label, host + ":" + port);
    }

    private static final class SimulatedGameConnection implements GameConnection {
        private final String accountId;
        private final String server;
        private volatile boolean active = true;
        /** push 监听（dry-run 下进频道后用定时推一条模拟增量，演示实时更新）。 */
        private final java.util.Map<String, java.util.function.Consumer<ApcObject>> pushListeners =
                new java.util.concurrent.ConcurrentHashMap<>();

        SimulatedGameConnection(String accountId, String server) {
            this.accountId = accountId;
            this.server = server;
        }

        @Override
        public void addPushListener(String callbackFunction, java.util.function.Consumer<ApcObject> listener) {
            pushListeners.put(callbackFunction, listener);
        }

        @Override
        public String accountId() {
            return accountId;
        }

        @Override
        public String server() {
            return server;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public ApcObject call(String requestFunction, String expectCallback, Duration timeout, Object... params) {
            var apc = new ApcObject();
            apc.setFunctionName(expectCallback);
            switch (requestFunction) {
                case "getRankNew" -> apc.setParameters(buildLadder(params));
                case "searchCharacterByName" -> apc.setParameters(buildSearch(params));
                case "getUserLocationData" -> apc.setParameters(buildLocation(params));
                case "getRoomChannel" -> apc.setParameters(buildChannels());
                case "getUserDataByLogin" -> apc.setParameters(buildUserData());
                case "channelAvaliable" -> apc.setParameters(buildChannelAvaliable(params));
                case "enterChannel" -> apc.setParameters(buildEnterChannel(params));
                case "roomList" -> {
                    apc.setParameters(buildRooms());
                    scheduleSimulatedDelta(); // dry-run：进频道后稍后推一条模拟增量，演示实时更新
                }
                default -> apc.setParameters(buildReduced(params));
            }
            return apc;
        }

        /** callBackGetUserDataByLogin：[ {含 token 的用户对象} ]（dry-run 给个合成 token 让房间链路可跑）。 */
        private List<Object> buildUserData() {
            var u = new LinkedHashMap<String, Object>();
            u.put("token", server + "-simtoken00000000000000000000000-0-1");
            u.put("characterId", 88888001L);
            u.put("loginId", 9);
            u.put("lv", 20); // dry-run 侦察号等级（落在多数模拟频道门槛内，房间链路可跑）
            return List.of(u);
        }

        /** onChannelAvaliable：[channelId, res]（res=1 授权成功）。 */
        private List<Object> buildChannelAvaliable(Object[] params) {
            int ch = params.length > 0 && params[0] instanceof Number n ? n.intValue() : 0;
            return List.of(ch, 1);
        }

        /** callEnterChannelSuccess：[res, channelId]（res=1 成功）。 */
        private List<Object> buildEnterChannel(Object[] params) {
            int ch = params.length > 1 && params[1] instanceof Number n ? n.intValue() : 0;
            return List.of(1, ch);
        }

        /** callBackShowRoomList：[ [RoomListItemInfo...] ]。合成几间不同玩法/状态/密码的房。 */
        private List<Object> buildRooms() {
            var rooms = new ArrayList<Object>();
            int[] race = {5, 4, 9, 3};
            int[] scene = {1, 530, 1, 55};
            int[] access = {3, 2, 1, 4};
            int[] status = {2, 0, 0, 2};
            for (int i = 0; i < race.length; i++) {
                rooms.add(room(32824348940000L + i, 10 + i, "模拟房间" + (i + 1),
                        race[i], scene[i], access[i], 12, status[i], i % 2 == 0, "房主" + (i + 1)));
            }
            return List.of(rooms);
        }

        private Map<String, Object> room(double roomId, int displayId, String name, int raceType,
                                         int sceneId, int access, int limit, int status,
                                         boolean encrypt, String owner) {
            var r = new LinkedHashMap<String, Object>();
            r.put("roomId", roomId);
            r.put("roomDisplayId", displayId);
            r.put("roomName", name);
            r.put("raceType", raceType);
            r.put("sceneId", sceneId);
            r.put("accessNumber", access);
            r.put("gameLimitNumber", limit);
            r.put("status", status);
            r.put("gameStatus", 0);
            r.put("isEncrypt", encrypt);
            r.put("createCharName", owner);
            r.put("channelId", 1);
            r.put("friendNumber", 0);
            return r;
        }

        /** dry-run：进频道 ~3s 后推一条 callBackAddRoom 增量，演示 worker 实时维护快照。 */
        private void scheduleSimulatedDelta() {
            var listener = pushListeners.get("callBackAddRoom");
            if (listener == null) {
                return;
            }
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!active) {
                    return;
                }
                var apc = new ApcObject();
                apc.setFunctionName("callBackAddRoom");
                apc.setParameters(List.of(1, room(32824348949999L, 99, "新增模拟房", 2003, 12, 1, 6, 0, false, "新房主"), 0));
                listener.accept(apc);
            });
        }

        /** onSearchCharacterByName：[ {角色对象} ]。 */
        private List<Object> buildSearch(Object[] params) {
            String name = params.length > 0 && params[0] != null ? params[0].toString() : "玩家";
            var c = new LinkedHashMap<String, Object>();
            c.put("characterId", 88888001L);
            c.put("characterName", name);
            c.put("teamName", "模拟战队");
            c.put("lv", 150);
            c.put("isOnline", true);
            c.put("logoutTime", 0L);
            c.put("dateline", 1777875797000L);
            return List.of(c);
        }

        /** onGetUserLocationData：位置数组 [charId, channel, room, _, inGame]。 */
        private List<Object> buildLocation(Object[] params) {
            long charId = params.length > 0 && params[0] instanceof Number n ? n.longValue() : 88888001L;
            return List.of(charId, server + "频道", 101, 0, true);
        }

        /** callChannelList：[ [ChannelInfo...], {好友数 map} ]。覆盖 free/crowd/full/vip 各状态。 */
        private List<Object> buildChannels() {
            String[] names = {"新手频道", "电信1频道", "电信2频道", "竞技频道", "生化频道"};
            int[] maxes = {1200, 2000, 2000, 1500, 1800};
            int[] nums = {180, 1400, 2000, 600, 1750};
            var channels = new ArrayList<Object>();
            for (int i = 0; i < names.length; i++) {
                var c = new LinkedHashMap<String, Object>();
                c.put("id", 100 + i);
                c.put("name", server + "区" + names[i]);
                c.put("number", nums[i]);
                c.put("maxClient", maxes[i]);
                c.put("limitMinLV", i == 0 ? 0 : 1);
                c.put("limitMaxLV", i == 0 ? 30 : 999);
                c.put("position", i);
                c.put("allowedRoomTypeId", 1);
                c.put("moreServerFlag", false);
                c.put("promiseToFightFlag", false);
                channels.add(c);
            }
            return List.of(channels, new LinkedHashMap<String, Object>());
        }

        /** [status, rankType, offset, pageSize, [bean.RankBean...], total]。 */
        private List<Object> buildReduced(Object[] params) {
            int rankType = params.length > 0 && params[0] instanceof Number n ? n.intValue() : 331;
            var rows = new ArrayList<Object>();
            for (int i = 0; i < SIM_TOTAL_ROWS; i++) {
                var info = new LinkedHashMap<String, Object>();
                info.put("teamName", "模拟队" + server);
                info.put("blueVipType", 0);
                info.put("loginId", 52);
                info.put("douwaVip", 0);
                info.put("homelandOpen", true);
                info.put("charId", 1000000L + i);
                info.put("lv", 600 - (i % 600));
                info.put("charName", "玩家" + server + "_" + (i + 1));
                info.put("operatorId", 1);
                info.put("blueVipLevel", 0);

                var score = new LinkedHashMap<String, Object>();
                score.put("allNumber", "13");
                score.put("number", String.valueOf((long) (SIM_TOTAL_ROWS - i) * 10000L));
                score.put("dateline", 1777875797044L);
                score.put("gcId", 1005200000000L + i);
                score.put("rebirth", 0);

                var bean = new LinkedHashMap<String, Object>();
                bean.put("lastRank", (long) (i + 1));
                bean.put("dateline", 1777875797050L);
                bean.put("updateDisplayCount", 0);
                bean.put("rankId", 0L);
                bean.put("display", true);
                bean.put("uniqueIdBean", 1005200000000L + i);
                bean.put("infoBean", info);
                bean.put("lastChangeDisplayFlag", 0);
                bean.put("scoreBean", score);
                rows.add(bean);
            }
            return List.of(1, rankType, 0, 10000, rows, SIM_TOTAL_ROWS);
        }

        /** [rankId, category, meta, offset, meta2, [bean.ClientRankKingBean...]]。 */
        private List<Object> buildLadder(Object[] params) {
            var rows = new ArrayList<Object>();
            for (int i = 0; i < SIM_TOTAL_ROWS; i++) {
                var bean = new LinkedHashMap<String, Object>();
                bean.put("teamName", "模拟队" + server);
                bean.put("lastRank", 0);
                bean.put("medalIndex", 0);
                bean.put("loginId", 6);
                bean.put("charId", 25740000L + i);
                bean.put("lv", 150);
                bean.put("userName", String.valueOf(1512573279L + i));
                bean.put("charName", "天梯" + server + "_" + (i + 1));
                bean.put("extraValue", 0);
                bean.put("medalLine", 0);
                bean.put("curRank", i + 1);
                bean.put("number", (long) (SIM_TOTAL_ROWS - i) * 100L);
                bean.put("teamId", 152921L);
                bean.put("operatorId", 0);
                rows.add(bean);
            }
            return List.of(23, List.of(4), 3, 0, 0, rows);
        }

        @Override
        public void send(String requestFunction, Object... params) {
            // no-op
        }

        @Override
        public void close() {
            active = false;
        }
    }
}
