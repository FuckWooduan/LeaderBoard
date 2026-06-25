package com.rankharvester.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rankharvester.account.AccountRegistry;
import com.rankharvester.account.GameAccount;
import com.rankharvester.account.GameAccountStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerSearchAndServerTest {

    @Test
    void game_server_map_resolves_id_name_host_port() {
        GameServer s = GameServer.fromIdOrThrow(15);
        assertThat(s.serverName()).isEqualTo("电信八区");
        assertThat(s.host()).isEqualTo("tc-sh-138.4399ssjj3.com");
        assertThat(s.port()).isEqualTo(2000);
        assertThat(s.districtId()).isEqualTo(24);
        assertThat(GameServer.nameOfId("13")).isEqualTo("电信七区");
        assertThat(GameServer.nameOfId("s1")).isEqualTo("s1"); // 非数字回退
    }

    @Test
    void player_search_returns_online_status_in_dryrun() {
        var accounts = mock(AccountRegistry.class);
        when(accounts.all()).thenReturn(List.of(new GameAccount("demo", "u", "p", List.of("15"))));

        var loginService = new GameLoginService(
                new SimulatedGameClient(), accounts, mock(GameAccountStore.class), null, 5);
        var svc = new PlayerSearchService(loginService, null); // 无侦察号 → 走抓榜号连接
        PlayerSearchResult r = svc.search("永恒唯一", 15);

        assertThat(r.character().characterName()).isEqualTo("永恒唯一");
        assertThat(r.character().isOnline()).isTrue();
        assertThat(r.character().lv()).isEqualTo(150);
        assertThat(r.location().inGame()).isTrue();
        assertThat(r.location().channelName()).isEqualTo("15频道");
    }
}
