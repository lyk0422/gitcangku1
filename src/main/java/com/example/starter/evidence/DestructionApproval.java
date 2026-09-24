package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 销毁令审批同意记录，对应 destruction_approval 表。只追加；
 * 同一销毁令同一审批人最多一条，两条互异且不同于提交人的记录凑齐后销毁令转 APPROVED。
 *
 * @param id             主键
 * @param destructionKey 所属销毁令业务键
 * @param approverId     同意的审批人
 * @param createdAt      同意时间（Asia/Shanghai）
 */
public record DestructionApproval(
        Long id,
        String destructionKey,
        String approverId,
        LocalDateTime createdAt) {
}
