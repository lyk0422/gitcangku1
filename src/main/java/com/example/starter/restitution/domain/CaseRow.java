package com.example.starter.restitution.domain;

/**
 * 案件行。
 *
 * @param id        案件编号
 * @param status    OPEN/DECIDED
 * @param version   案件版本（乐观锁）
 * @param createdAt 创建时间（epoch 毫秒）
 * @param decidedAt 裁决时间（epoch 毫秒），未裁决为 null
 */
public record CaseRow(String id, String status, long version, long createdAt, Long decidedAt) {

    public static final String OPEN = "OPEN";
    public static final String DECIDED = "DECIDED";
}
