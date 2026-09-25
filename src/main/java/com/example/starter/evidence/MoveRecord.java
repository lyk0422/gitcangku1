package com.example.starter.evidence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 双人迁移记录实体，对应 move_record 表。第二人确认执行成功时写入，只追加、不可变。
 *
 * @param id              主键
 * @param moveKey         关联迁移单业务键，唯一
 * @param evidenceKeys    迁移证物键集合（规范化）
 * @param sourceLocation  迁出库位编码
 * @param targetLocation  迁入库位编码
 * @param expectedVersion 执行时校验通过的源库位库存版本
 * @param firstConfirmer  首人确认保管人
 * @param secondConfirmer 第二人确认保管人，与首人不同
 * @param completedAt     迁移执行完成时间（Asia/Shanghai）
 */
public record MoveRecord(
        Long id,
        String moveKey,
        List<String> evidenceKeys,
        String sourceLocation,
        String targetLocation,
        int expectedVersion,
        String firstConfirmer,
        String secondConfirmer,
        LocalDateTime completedAt) {
}
