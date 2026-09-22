package com.example.starter.domain;

import java.util.Objects;

/**
 * 操作者身份，来自受信任的本地测试请求头 X-Actor-Id / X-Role。
 *
 * @param actorId 操作者编号
 * @param role    操作者角色
 */
public record Actor(String actorId, Role role) {

    /** 受信任角色：COORDINATOR 协调员，REVIEWER 审阅员。 */
    public enum Role {
        COORDINATOR,
        REVIEWER
    }

    public Actor {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(role, "role");
    }
}
