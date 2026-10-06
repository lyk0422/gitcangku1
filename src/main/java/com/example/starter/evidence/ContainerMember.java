package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 箱成员实体，对应 container_member 表。
 * (memberType, memberKey) 全局唯一：任一证物或子箱仅能有一个直接父箱。
 *
 * @param id           主键
 * @param containerKey 所属箱业务键（直接父箱）
 * @param memberType   成员类型：EVIDENCE 证物 / BOX 子箱
 * @param memberKey    成员业务键
 * @param createdAt    装箱时间（Asia/Shanghai）
 */
public record ContainerMember(
        Long id,
        String containerKey,
        String memberType,
        String memberKey,
        LocalDateTime createdAt) {

    /**
     * 成员类型：证物。
     */
    public static final String TYPE_EVIDENCE = "EVIDENCE";

    /**
     * 成员类型：子箱。
     */
    public static final String TYPE_BOX = "BOX";
}
