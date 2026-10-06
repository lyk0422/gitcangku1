package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 箱封条核验记录，对应 container_seal_inspection 表。只追加、不可变；
 * 核验失败使该箱 seal_status 变为 BROKEN（不影响成员各自封条）。
 *
 * @param id           主键
 * @param containerKey 被核验箱业务键
 * @param inspectorId  提交核验的当前保管人
 * @param passed       核验结果：true 通过 / false 失败
 * @param note         核验备注；NULL 表示未填写
 * @param createdAt    核验提交时间（Asia/Shanghai）
 */
public record ContainerSealInspection(
        Long id,
        String containerKey,
        String inspectorId,
        boolean passed,
        String note,
        LocalDateTime createdAt) {
}
