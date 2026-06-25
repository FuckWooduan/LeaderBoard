package com.rankharvester.net;

/**
 * 玩家查询结果（移植自 StrikeGod.Engine）：角色信息（含在线标志）+ 位置信息。
 *
 * @param character 角色信息（{@code isOnline} 即“是否在线”）
 * @param location  位置信息（{@code inGame} 佐证实时在场）
 */
public record PlayerSearchResult(CharacterInfo character, LocationInfo location) {

    /** {@code onSearchCharacterByName} 回包字段。 */
    public record CharacterInfo(
            long characterId,
            String characterName,
            String teamName,
            int lv,
            boolean isOnline,
            long logoutTime,
            long dateline) {}

    /** {@code onGetUserLocationData} 回包（位置数组）字段。 */
    public record LocationInfo(long characterId, String channelName, int roomId, boolean inGame) {}
}
