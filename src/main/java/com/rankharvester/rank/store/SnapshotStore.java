package com.rankharvester.rank.store;

import com.rankharvester.rank.model.RankRow;
import com.rankharvester.rank.model.RankSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * 排行榜快照存储（只留最新一份）。
 *
 * <p>实现分两层：DuckDB 列式持久化（重启不丢、按名查人）+ Redis 热缓存（毫秒级公开读）。
 * QQBot / Web 不直连本存储，统一经 IPC 走 Engine（与主仓硬规则一致）。
 */
public interface SnapshotStore {

    /** 完整成功后整份覆盖写入（半截榜不调用此方法）。 */
    void writeLatest(RankSnapshot snapshot);

    /** 读取某榜最新快照元信息（不含全部行）。 */
    Optional<RankSnapshot> latestMeta(String leaderboardCode);

    /** 分页读取最新快照的行。 */
    List<RankRow> page(String leaderboardCode, int offset, int limit);

    /** 按玩家/战队名在最新快照中查询（模糊或精确由实现决定）。 */
    List<RankRow> findByName(String leaderboardCode, String name, int limit);
}
