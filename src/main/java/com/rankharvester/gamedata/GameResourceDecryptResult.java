package com.rankharvester.gamedata;

/**
 * 游戏加密资源解密结果。
 *
 * @param success 是否解密成功；失败时 {@code data} 为原始未解密字节
 * @param data    解密后字节（.bin 为 JSON UTF-8；.swf/.xml 为解压后内容）
 */
public record GameResourceDecryptResult(boolean success, byte[] data) {}
