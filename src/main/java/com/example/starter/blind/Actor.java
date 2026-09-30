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

    /** 中心负责人：中心配置与启用申请。 */
    public boolean isLead() {
        return role == Role.LEAD;
    }

    /** 独立确认人：核对区组及名额后确认启用。 */
    public boolean isConfirmer() {
        return role == Role.CONFIRMER;
    }
}
