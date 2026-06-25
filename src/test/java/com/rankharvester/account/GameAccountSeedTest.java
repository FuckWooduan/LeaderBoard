package com.rankharvester.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 账号种子：COPY 解析 + DuckDB 往返 + 可选的真实 dump 导入。 */
class GameAccountSeedTest {

    private static final String SAMPLE =
            """
            SET row_security = off;

            COPY public.game_account (id, created_at, updated_at, account, available, checked_at, enabled, last_used_at, password, pauth, servers, uid, next_pauth_check_at) FROM stdin;
            843\t2026-05-09 11:18:35.7\t2026-05-24 16:03:15.2\tsgttrhnvue\tt\t2026-05-24 16:03:15.2\tt\t\\N\tlyzOFJwKzA\t1337467295|sgttrhnvue|abc|1779476904|10002|def|0\t[13, 11, 9]\t1337467295\t2026-06-23 16:03:15.2
            999\t2026-05-23 00:11:39.7\t\\N\tdisabledacc\tf\t\\N\tf\t\\N\tPwD123\t\\N\t[1]\t\\N\t\\N
            \\.
            """;

    @Test
    void parses_copy_block_with_nulls_and_booleans() {
        List<PgGameAccountDump.SeedRow> rows = PgGameAccountDump.parse(SAMPLE);

        assertThat(rows).hasSize(2);

        var a = rows.getFirst();
        assertThat(a.id()).isEqualTo(843L);
        assertThat(a.account()).isEqualTo("sgttrhnvue");
        assertThat(a.password()).isEqualTo("lyzOFJwKzA");
        assertThat(a.pauth()).startsWith("1337467295|sgttrhnvue");
        assertThat(a.uid()).isEqualTo("1337467295");
        assertThat(a.serversJson()).isEqualTo("[13, 11, 9]");
        assertThat(a.available()).isTrue();
        assertThat(a.enabled()).isTrue();
        assertThat(a.lastUsedAt()).isNull(); // \N

        var b = rows.get(1);
        assertThat(b.id()).isEqualTo(999L);
        assertThat(b.available()).isFalse();
        assertThat(b.enabled()).isFalse();
        assertThat(b.pauth()).isNull();
        assertThat(b.uid()).isNull();
    }

    @Test
    void servers_json_parses_to_string_list() {
        assertThat(GameAccountStore.parseServers("[13, 11, 9]")).containsExactly("13", "11", "9");
        assertThat(GameAccountStore.parseServers("[]")).isEmpty();
        assertThat(GameAccountStore.parseServers(null)).isEmpty();
    }

    @Test
    void duckdb_roundtrip_maps_to_domain_accounts() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            var store = new GameAccountStore(conn);
            store.init();
            assertThat(store.count()).isZero();

            int inserted = store.insertBatch(PgGameAccountDump.parse(SAMPLE));
            assertThat(inserted).isEqualTo(2);
            assertThat(store.count()).isEqualTo(2);

            List<GameAccount> all = store.all();
            assertThat(all).hasSize(2);
            var first = all.getFirst();
            assertThat(first.id()).isEqualTo("843");
            assertThat(first.account()).isEqualTo("sgttrhnvue");
            assertThat(first.servers()).containsExactly("13", "11", "9");
            assertThat(first.pauth()).isNotNull();
            assertThat(first.enabled()).isTrue();
            assertThat(first.available()).isTrue();
        }
    }

    /** 若真实 dump 存在则验证整文件可解析（数量约 3078）。文件缺失时跳过。 */
    @Test
    void parses_real_dump_if_present() throws Exception {
        Path dump = Path.of(
                "/Users/birditch/Downloads/strikegod_2026-05-29_17-09-07_pgsql_data/public.game_account.sql");
        assumeTrue(Files.isRegularFile(dump), "真实 dump 不存在，跳过");

        List<PgGameAccountDump.SeedRow> rows = PgGameAccountDump.parse(Files.readString(dump));
        assertThat(rows).hasSizeGreaterThan(3000);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.id()).isGreaterThan(0);
            assertThat(r.account()).isNotBlank();
            assertThat(r.password()).isNotBlank();
        });
    }
}
