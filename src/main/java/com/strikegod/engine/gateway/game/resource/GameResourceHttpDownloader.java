package com.strikegod.engine.gateway.game.resource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/**
 * 游戏资源 CDN 字节下载工具。
 */
@UtilityClass
public class GameResourceHttpDownloader {

    public static byte[] downloadBytes(HttpClient httpClient, String url, Duration timeout) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(timeout)
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP 下载失败，状态码: " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP 下载被中断: " + url, e);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 下载失败: " + url, e);
        }
    }
}
