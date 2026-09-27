package com.example.starter.evidence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库位迁移单实体，对应 location_move_order 表。双人确认制：
 * 首人确认（申请）创建迁移单；第二人确认在同一事务内完成整单迁移并写入封签快照。
 * 迁移单只追加一次，完成或撤销后历史字段不可改写。
 *
 * @param id                 主键
 * @param moveKey            迁移业务键，全局唯一
 * @param evidenceKeys       规范化（升序）证物集合；换序视为同参
 * @param sourceLocationCode 源库位编码
 * @param targetLocationCode 目标库位编码
 * @param expectedVersion    期望的每件证物库位版本号
 * @param requestedBy        首人确认（申请）操作人
 * @param status             迁移单状态
 * @param firstConfirmedBy   首人确认操作人
 * @param firstConfirmedAt   首人确认时间（Asia/Shanghai）
 * @param secondConfirmedBy  第二人确认操作人；null 表示未确认
 * @param secondConfirmedAt  第二人确认时间（Asia/Shanghai）；null 表示未确认
 * @param cancelledBy        撤销操作人；null 表示未撤销
 * @param cancelledAt        撤销时间（Asia/Shanghai）；null 表示未撤销
 * @param createdAt          迁移单创建时间（Asia/Shanghai）
 */
public record LocationMoveOrder(
        Long id,
        String moveKey,
        List<String> evidenceKeys,
        String sourceLocationCode,
        String targetLocationCode,
        long expectedVersion,
        String requestedBy,
        LocationMoveStatus status,
        String firstConfirmedBy,
        LocalDateTime firstConfirmedAt,
        String secondConfirmedBy,
        LocalDateTime secondConfirmedAt,
        String cancelledBy,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt) {
}
