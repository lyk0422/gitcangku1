package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 箱封条核验记录，对应 container_inspection 表。记录只追加、不可变。
 *
 * @param id           主键
 * @param containerKey 关联箱业务键
 * @param inspectorId  提交核验的当前保管人
 * @param passed       核验结果：true 通过 / false 失败（箱进入 SEAL_BROKEN）
 * @param note         核验备注；NULL 表示未填写
 * @param createdAt    核验提交时间（Asia/Shanghai）
 */
public record ContainerInspection(
        Long id,
        String containerKey,
        String inspectorId,
        boolean passed,
        String note,
        LocalDateTime createdAt) {
}
