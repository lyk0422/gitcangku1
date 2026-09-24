package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 销毁令实体，对应 destruction_order 表。
 * 销毁键与法律依据等业务字段创建后不可修改；status 随双人审批/拒绝/执行流转。
 *
 * @param id                 主键
 * @param destructionKey     销毁令业务键，全局唯一
 * @param submittedBy        提交保管人（执行时仍须由此人提交）
 * @param legalBasis        非空法律依据编号
 * @param destructionMethod 销毁方式，非空
 * @param forceIncludeBroken 是否显式允许封条异常证物入列
 * @param status            销毁令状态
 * @param rejectReason      拒绝原因；仅 REJECTED 时非空，写入后不可改写
 * @param createdAt         创建时间（Asia/Shanghai）
 * @param decidedAt         双人审批完成或拒绝时间；NULL 表示仍在 PENDING
 * @param executedAt        实际执行销毁时间；NULL 表示未执行
 * @param updatedAt         最近一次状态变更时间（Asia/Shanghai）
 */
public record DestructionOrder(
        Long id,
        String destructionKey,
        String submittedBy,
        String legalBasis,
        String destructionMethod,
        boolean forceIncludeBroken,
        DestructionStatus status,
        String rejectReason,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        LocalDateTime executedAt,
        LocalDateTime updatedAt) {
}
