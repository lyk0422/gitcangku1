package com.example.starter.evidence.destruction.dto;

/**
 * 证物冻结状态视图。证物被 PENDING/APPROVED 销毁令入列期间 frozen=true，
 * 并返回冻结它的 destructionKey；终态销毁令（REJECTED/DESTROYED 之后）不再冻结。
 *
 * @param evidenceKey    证物业务键
 * @param frozen         是否被未终结销毁令冻结
 * @param destructionKey 冻结它的销毁令键；未冻结为 null
 * @param evidenceStatus 证物当前状态
 */
public record EvidenceFreezeView(
        String evidenceKey,
        boolean frozen,
        String destructionKey,
        String evidenceStatus) {
}
