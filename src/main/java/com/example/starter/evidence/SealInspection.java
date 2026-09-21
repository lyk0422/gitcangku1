package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 封条核验记录实体，对应 seal_inspection 表。记录只追加、不可变。
 *
 * @param id          主键
 * @param evidenceKey 关联证物业务键
 * @param inspectorId 提交核验的当前保管人
 * @param passed      核验结果：true 通过 / false 失败（证物进入 SEAL_BROKEN）
 * @param note        核验备注；null 表示未填写
 * @param createdAt   核验提交时间（Asia/Shanghai）
 */
public record SealInspection(
        Long id,
        String evidenceKey,
        String inspectorId,
        boolean passed,
        String note,
        LocalDateTime createdAt) {
}
