package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 封签核验快照实体，对应 seal_snapshot 表。迁移执行时逐件写入，只追加、不可变。
 *
 * @param id              主键
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
public record SealSnapshot(
        Long id,
        String moveKey,
        String evidenceKey,
        String sealNo,
        String sealStatus,
        String fromLocation,
        String toLocation,
        String firstConfirmer,
        String secondConfirmer,
        LocalDateTime createdAt) {

    /**
     * 封签完好状态标识。
     */
    public static final String STATUS_INTACT = "INTACT";
}
