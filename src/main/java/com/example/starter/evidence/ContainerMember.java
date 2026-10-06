package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 容器成员记录，对应 container_member 表。
 * (memberType, memberKey) 全局唯一，保证证物或子箱仅能有一个直接父箱；删除行即拆箱。
 *
 * @param id           主键
 * @param containerKey 父箱业务键
 * @param memberType   成员类型（EVIDENCE / CONTAINER）
 * @param memberKey    成员业务键（证物键或子箱键）
 * @param createdAt    装箱时间（Asia/Shanghai）
 */
public record ContainerMember(
        Long id,
        String containerKey,
        MemberType memberType,
        String memberKey,
        LocalDateTime createdAt) {
}
