package com.rankharvester.net;

/**
 * loginV3 返回码 4：该账号在该大区被封禁。调用方应将该 (账号×大区) 从候选中剔除并换号重试。
 */
public class BannedOnServerException extends RuntimeException {

    private final String accountId;
    private final String server;

    public BannedOnServerException(String accountId, String server) {
        super("账号 " + accountId + " 在大区 " + server + " 被封禁(loginV3 rc=4)");
        this.accountId = accountId;
        this.server = server;
    }

    public String accountId() {
        return accountId;
    }

    public String server() {
        return server;
    }
}
