package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 销毁阻断冻结快照，对应 destruction_block_snapshot 表。
 * 申请转 HOLD_BLOCKED 瞬间写入，之后冻结解除/过期均不改写。
 *
 * @param id          主键
 * @param requestPk   关联销毁申请主键
 * @param holdId      命中冻结业务键
 * @param holdVersion 阻断瞬间冻结版本
 * @param caseKey     阻断瞬间冻结案件号
 * @param effectiveAt 阻断瞬间冻结 UTC 生效时刻
 * @param expireAt    阻断瞬间冻结 UTC 失效时刻
 * @param reason      阻断瞬间冻结原因
 * @param order       holdId 稳定排序序号
 */
public record DestructionBlockSnapshot(
        Long id,
        long requestPk,
        String holdId,
        int holdVersion,
        String caseKey,
        LocalDateTime effectiveAt,
        LocalDateTime expireAt,
        String reason,
        int order) {
}
