package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 封签核验快照视图（不可变）。
 *
 * @param moveKey         关联迁移单业务键
 * @param evidenceKey     证物业务键
 * @param sealNo          迁移执行时封条编号快照
 * @param sealStatus      迁移执行时封签状态：INTACT 完好
 * @param fromLocation    迁出库位编码
 * @param toLocation      迁入库位编码
 * @param firstConfirmer  首人确认保管人
 * @param secondConfirmer 第二人确认保管人
 * @param createdAt       快照写入时间（Asia/Shanghai）
 */
public record SealSnapshotView(
        String moveKey,
        String evidenceKey,
        String sealNo,
        String sealStatus,
        String fromLocation,
        String toLocation,
        String firstConfirmer,
        String secondConfirmer,
        LocalDateTime createdAt) {
}
