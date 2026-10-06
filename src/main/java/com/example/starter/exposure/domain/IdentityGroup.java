package com.example.starter.exposure.domain;

/**
 * 身份归并组：将多个合成访客编号显式并为一组，组关系全局共享（不按公告区分）。
 *
 * @param groupId      归并组编号，全局唯一
 * @param version      乐观锁版本：每次成员变更 +1，初始 1；未归并访客视为版本 0
 * @param memberCount  组内成员数，上限 10
 * @param lastOperator 最近一次归并的操作者
 * @param updatedAtUtc 最近一次归并时刻，epoch 毫秒，UTC
 */
public record IdentityGroup(String groupId, int version, int memberCount,
                            String lastOperator, long updatedAtUtc) {
}
