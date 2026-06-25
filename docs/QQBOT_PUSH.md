# QQ 群推送接口说明

## 概述

rank-harvester 通过 NapCat OneBot v11 HTTP API 主动向 QQ 群推送排行榜消息。
推送是单向的：app 作为 HTTP 客户端调用 NapCat，NapCat 代发消息到 QQ 群。

---

## RankPushService 接口

```java
package com.rankharvester.qqbot;

public interface RankPushService {

    /** 向所有配置的群（rankharvester.qqbot.group-ids）推送文本消息 */
    void pushText(String text);

    /** 向指定群推送文本消息 */
    void pushText(long groupId, String text);

    /** 推送是否已启用（enabled=false 时所有方法静默） */
    boolean enabled();
}
```

### 用法示例

```java
@Component
public class RankPublisher {

    private final RankPushService pushService;

    public RankPublisher(RankPushService pushService) {
        this.pushService = pushService;
    }

    public void publishResult(String summary) {
        // 向所有配置群推送
        pushService.pushText(summary);
    }

    public void publishToSpecificGroup(long groupId, String text) {
        // 向指定群推送
        pushService.pushText(groupId, text);
    }
}
```

### 注意事项

- `enabled=false`（默认）时，所有 push 方法只打 debug 日志，不实际发送，不报错。
- 任何网络异常或 HTTP 非 2xx 均被内部 catch，只打 warn 日志，**不向调用方抛异常**。
- 推送失败不会影响主流程（抓取、存储等）。

---

## 配置属性

前缀：`rankharvester.qqbot`

| 属性                | 类型          | 默认值                    | 说明                                    |
|-------------------|-------------|--------------------------|---------------------------------------|
| `enabled`         | boolean     | `false`                  | 是否启用推送；false 时静默                |
| `http-base-url`   | String      | `http://napcat:3000`     | NapCat OneBot HTTP API 基础地址         |
| `access-token`    | String      | `""`（空）               | Bearer Token；空时不带 Authorization    |
| `group-ids`       | List\<Long\>| `[]`（空）               | 推送目标群号列表                         |
| `timeout-seconds` | int         | `10`                     | 每次 HTTP 请求的超时秒数                 |

`application.yml` 配置示例：

```yaml
rankharvester:
  qqbot:
    enabled: true
    http-base-url: http://napcat:3000
    access-token: your_access_token_here
    group-ids:
      - 123456789
      - 987654321
    timeout-seconds: 10
```

---

## OneBot v11 send_group_msg 载荷格式

### 请求

```
POST http://napcat:3000/send_group_msg
Content-Type: application/json;charset=UTF-8
Authorization: Bearer <access_token>   （token 非空时）
```

```json
{
  "group_id": 123456789,
  "message": "【排行榜更新】\n第1名：玩家A — 99999分\n第2名：玩家B — 88888分"
}
```

### 响应（成功）

```json
{
  "status": "ok",
  "retcode": 0,
  "data": {
    "message_id": 12345
  }
}
```

### 响应（失败）

```json
{
  "status": "failed",
  "retcode": 1404,
  "msg": "群不存在"
}
```

HTTP 状态码非 2xx 或 retcode 非 0 时，`OneBotPushService` 打 warn 日志，不抛异常。

---

## send_group_forward_msg（合并转发，可选扩展）

当消息内容较长（如完整排行榜明细）时，可使用合并转发格式，在 QQ 中折叠展示。

### 请求端点

```
POST http://napcat:3000/send_group_forward_msg
```

### 载荷格式

```json
{
  "group_id": 123456789,
  "messages": [
    {
      "type": "node",
      "data": {
        "name": "排行榜机器人",
        "uin": "机器人QQ号",
        "content": "第1名：玩家A — 99999分"
      }
    },
    {
      "type": "node",
      "data": {
        "name": "排行榜机器人",
        "uin": "机器人QQ号",
        "content": "第2名：玩家B — 88888分"
      }
    }
  ]
}
```

### 扩展建议

如需支持合并转发，可在 `RankPushService` 接口中新增：

```java
/** 向所有配置群发送合并转发消息（节点列表） */
void pushForward(List<String> nodes);
```

当前 `OneBotPushService` 仅实现纯文本推送，合并转发为未来可选扩展，
不影响现有 `pushText` 接口的稳定性。

---

## 参考资料

- [OneBot v11 规范](https://github.com/botuniverse/onebot-11)
- [NapCat 项目](https://github.com/NapNeko/NapCatQQ)
- `src/main/java/com/rankharvester/qqbot/OneBotPushService.java`
- `docs/DEPLOY.md` — NapCat 容器部署与 access_token 配置
