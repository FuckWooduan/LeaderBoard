package com.rankharvester.net;

import com.rankharvester.apc.ApcCodec;
import com.rankharvester.apc.ApcObject;
import com.rankharvester.apc.ApcPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APC 入站解码器：将上游 {@code LengthFieldBasedFrameDecoder} 切出的完整帧
 * （含 4 字节长度前缀）解码为 {@link ApcObject}。
 *
 * <p>帧格式：[4字节长度][1字节压缩标志][AMF3 载荷]，复用 {@link ApcCodec#parseByteStream}。
 */
public class ApcInboundDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(ApcInboundDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        var bytes = new byte[msg.readableBytes()];
        msg.readBytes(bytes);

        for (ApcPacket packet : ApcCodec.parseByteStream(bytes)) {
            if (packet.isHasError()) {
                log.warn("APC 入站解析失败: {}", packet.getError());
                continue;
            }
            var apc = packet.getApcObj();
            if (apc != null) {
                out.add(apc);
            }
        }
    }
}
