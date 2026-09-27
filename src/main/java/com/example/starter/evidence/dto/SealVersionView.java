package com.example.starter.evidence.dto;

import com.example.starter.evidence.SealVersionEvent;
import com.example.starter.evidence.SealVersionKind;

import java.time.LocalDateTime;

/**
 * 封签版本视图（只追加、不可变）。
 *
 * @param version      版本号，同一证物从 1 起严格递增
 * @param sealNo       该版本封签编号
 * @param kind         版本类型：ORIGINAL/RESEALED
 * @param createdBy    产生该版本的操作人
 * @param eventType    产生事件：INTAKE/EXAMINATION_COMPLETED
 * @param createdAt    版本产生时间（UTC）
 * @param supersededAt 被检验开始锁定的时间（UTC）；null 表示当前有效版本
 */
public record SealVersionView(
        int version,
        String sealNo,
        SealVersionKind kind,
        String createdBy,
        SealVersionEvent eventType,
        LocalDateTime createdAt,
        LocalDateTime supersededAt) {
}
