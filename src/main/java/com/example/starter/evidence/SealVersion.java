package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物封签版本实体，对应 seal_version 表。封签版本只追加、不可变：
 * 入库时生成版本 1（ORIGINAL）；检验开始时锁定当时原封签版本；
 * 检验完成校验新封签后追加下一版本（RESEALED）。后续任何操作不得改写旧版本。
 *
 * @param id           主键
 * @param evidenceKey  关联证物业务键
 * @param version      封签版本号，同一证物从 1 起严格递增
 * @param sealNo       该版本封签编号（新封签可与旧封签不同）
 * @param kind         版本类型：ORIGINAL 入库原封签 / RESEALED 检验完成后的新封签
 * @param createdBy    产生该版本的操作人
 * @param eventType    产生该版本的保管链事件类型：INTAKE 入库 / EXAMINATION_COMPLETED 检验完成
 * @param createdAt    版本产生时间（UTC）
 * @param supersededAt 被检验开始锁定（旧版本封存）的时间（UTC）；NULL 表示当前有效版本
 */
public record SealVersion(
        Long id,
        String evidenceKey,
        int version,
        String sealNo,
        SealVersionKind kind,
        String createdBy,
        SealVersionEvent eventType,
        LocalDateTime createdAt,
        LocalDateTime supersededAt) {
}
