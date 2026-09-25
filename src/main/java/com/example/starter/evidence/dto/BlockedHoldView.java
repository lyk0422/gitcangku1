package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 销毁阻断命中冻结的不可变快照视图（申请转 HOLD_BLOCKED 瞬间的冻结状态）。
 *
 * @param holdId      命中冻结业务键
 * @param version     阻断瞬间冻结版本
 * @param caseKey     阻断瞬间案件号
 * @param effectiveAt 阻断瞬间 UTC 生效时刻
 * @param expireAt    阻断瞬间 UTC 失效时刻
 * @param reason      阻断瞬间冻结原因
 */
public record BlockedHoldView(
        String holdId,
        int version,
        String caseKey,
        LocalDateTime effectiveAt,
        LocalDateTime expireAt,
        String reason) {
}
