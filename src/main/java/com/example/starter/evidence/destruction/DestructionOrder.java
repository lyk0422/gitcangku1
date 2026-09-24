package com.example.starter.evidence.destruction;

import java.time.LocalDateTime;

/**
 * 销毁令实体，对应 destruction_order 表。创建后只允许状态推进：
 * PENDING -> APPROVED -> DESTROYED，或 PENDING -> REJECTED；终态不可再变。
 *
 * @param id                 主键
 * @param destructionKey     销毁令业务键，全局唯一
 * @param submitterId        提交销毁令的当前保管人
 * @param legalBasis         非空法律依据编号
 * @param destructionMethod  非空销毁方式
 * @param forceIncludeBroken 是否显式声明允许封条异常证物入列
 * @param status             销毁令状态
 * @param rejectReason       拒绝原因；null 表示未拒绝；拒绝写入后不可改写
 * @param rejectedBy         拒绝审批人；null 表示未拒绝
 * @param rejectedAt         拒绝时间（Asia/Shanghai）；null 表示未拒绝
 * @param approvedAt         第二次同意转 APPROVED 的时间；null 表示未批准
 * @param destroyedAt        实际执行时间（Asia/Shanghai）；null 表示未执行
 * @param createdAt          创建时间（Asia/Shanghai）
 * @param updatedAt          最近状态变更时间（Asia/Shanghai）
 */
public record DestructionOrder(
        Long id,
        String destructionKey,
        String submitterId,
        String legalBasis,
        String destructionMethod,
        boolean forceIncludeBroken,
        DestructionStatus status,
        String rejectReason,
        String rejectedBy,
        LocalDateTime rejectedAt,
        LocalDateTime approvedAt,
        LocalDateTime destroyedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
