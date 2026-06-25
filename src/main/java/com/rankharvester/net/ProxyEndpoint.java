package com.rankharvester.net;

/** 一条代理端点（SOCKS5，带账号密码鉴权）。 */
public record ProxyEndpoint(String host, int port, String account, String password) {}
