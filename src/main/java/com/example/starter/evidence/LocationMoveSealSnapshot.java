package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 迁移封签核验快照实体，对应 location_move_seal_snapshot 表。
 * 第二人确认时对每件证物写入一条，只追加、不可变，不提供任何更新语句。
 *
 * @param id              主键
 * @param moveKey         关联迁移业务键
 * @param evidenceKey     关联证物业务键
 * @param sealNo          迁移确认时刻的封条编号快照
 * @param sealIntact      迁移确认时刻封条是否正常（异常则整单回滚，不会产生快照）
 * @param locationCode    迁移确认时刻的源库位编码快照
 * @param locationVersion 迁移确认时刻的库位版本号快照（迁移前版本）
 * @param checkedBy       第二人确认操作人
 * @param checkedAt       快照写入时间（Asia/Shanghai）
 */
public record LocationMoveSealSnapshot(
        Long id,
        String moveKey,
        String evidenceKey,
        String sealNo,
        boolean sealIntact,
        String locationCode,
        long locationVersion,
        String checkedBy,
        LocalDateTime checkedAt) {
}
