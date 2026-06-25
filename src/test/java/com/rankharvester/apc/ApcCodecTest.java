package com.rankharvester.apc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** APC 编解码往返测试（移植自主仓 Engine 的核心协议）。 */
class ApcCodecTest {

    @Test
    void quickRoundTrip() {
        var hex = ApcCodec.quick("buyRpgShop", 1000);
        var apc = ApcCodec.parseFirstObj(hex);

        assertThat(apc.getFunctionName()).isEqualTo("buyRpgShop");
        assertThat(apc.getParameters()).containsExactly(1000);
        assertThat(apc.isTrace()).isTrue();
    }

    @Test
    void mixedTypesRoundTrip() {
        var hex = ApcCodec.buildPacketHex(
                "setInfo", List.of("weapon", 42, 3.14, true, List.of(1, 2, 3)), true, true);
        var apc = ApcCodec.parseFirstObj(hex);

        assertThat(apc.getFunctionName()).isEqualTo("setInfo");
        assertThat(apc.getParameters()).hasSize(5);
        assertThat(apc.getParameters().get(0)).isEqualTo("weapon");
        assertThat(apc.getParameters().get(1)).isEqualTo(42);
        assertThat(apc.getParameters().get(2)).isEqualTo(3.14);
        assertThat(apc.getParameters().get(3)).isEqualTo(true);
        assertThat(apc.getParameters().get(4)).isInstanceOf(List.class);
    }

    @Test
    void nestedMapRoundTrip() {
        var hex = ApcCodec.buildPacketHex(
                "callbackGetReducedRankByPage",
                List.of(1, 4, 0, 50, List.of(Map.of("rank", 1, "playerName", "玩家A", "power", 99999))),
                true,
                true);
        var apc = ApcCodec.parseFirstObj(hex);

        assertThat(apc.getFunctionName()).isEqualTo("callbackGetReducedRankByPage");
        Object rows = apc.getParameters().get(4);
        assertThat(rows).isInstanceOf(List.class);
        Object first = ((List<?>) rows).getFirst();
        assertThat(first).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) first).get("playerName")).isEqualTo("玩家A");
    }

    @Test
    void heartbeatParses() {
        var bytes = ApcCodec.heartbeatBytes();
        var packets = ApcCodec.parseByteStream(bytes);

        assertThat(packets).hasSize(1);
        assertThat(packets.getFirst().isHasError()).isFalse();
        assertThat(packets.getFirst().getApcObj().getFunctionName()).isEqualTo("heartBeat");
        assertThat(packets.getFirst().getApcObj().getParameters()).isEmpty();
    }

    @Test
    void uncompressedRoundTrip() {
        // 不压缩路径；注意 isTrace=false 时协议不写该字段，解析回来按默认 true（与 AS3/C# 一致）。
        var hex = ApcCodec.buildPacketHex("ping", List.of(), false, false);
        var apc = ApcCodec.parseFirstObj(hex);

        assertThat(apc.getFunctionName()).isEqualTo("ping");
        assertThat(apc.getParameters()).isEmpty();
    }

    @Test
    void stickyPacketsParseAll() {
        var a = ApcCodec.quick("ping");
        var b = ApcCodec.quick("heartBeat");
        var packets = ApcCodec.parseHexStream(a + b);

        assertThat(packets).hasSize(2);
        assertThat(packets.get(0).getApcObj().getFunctionName()).isEqualTo("ping");
        assertThat(packets.get(1).getApcObj().getFunctionName()).isEqualTo("heartBeat");
    }
}
