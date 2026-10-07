package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 污染事件视图，含冻结的传播路径。
 *
 * @param contaminationId  污染事件主键
 * @param originEvidenceKey 污染起点证物业务键
 * @param originEventSeq   污染起点接触事件序号
 * @param registeredBy     登记操作人
 * @param contaminatedKeys 本次被隔离的全部证物业务键（按证物键排序）
 * @param createdAt        污染登记时刻（UTC）
 */
public record ContaminationView(
        long contaminationId,
        String originEvidenceKey,
        long originEventSeq,
        String registeredBy,
        List<String> contaminatedKeys,
        LocalDateTime createdAt) {
}
