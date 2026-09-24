package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 销毁令入列证物行，对应 destruction_order_item 表。只追加，创建后不可变。
 *
 * @param id             主键
 * @param destructionKey 所属销毁令业务键
 * @param evidenceKey    入列证物业务键
 * @param includedStatus 入列时证物状态快照（SEALED，或显式强制入列的 SEAL_BROKEN），执行时重查比对
 * @param forcedBroken   入列时该证物是否封条异常且经 forceIncludeBroken 显式放行
 * @param createdAt      入列时间（Asia/Shanghai）
 */
public record DestructionOrderItem(
        Long id,
        String destructionKey,
        String evidenceKey,
        EvidenceStatus includedStatus,
        boolean forcedBroken,
        LocalDateTime createdAt) {
}
