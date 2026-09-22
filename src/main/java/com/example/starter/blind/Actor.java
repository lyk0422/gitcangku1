package com.example.starter.blind;

/**
 * 操作者身份，来源于受信任的本地测试头 X-Actor-Id / X-Role。
 *
 * @param actorId 操作者编号
 * @param role    角色（COORDINATOR / REVIEWER）
 */
public record Actor(String actorId, Role role) {

    public boolean isCoordinator() {
        return role == Role.COORDINATOR;
    }

    public boolean isReviewer() {
        return role == Role.REVIEWER;
    }
}
