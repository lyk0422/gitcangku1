package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 巡检见证人实体，对应 inspection_witness 表。同一巡检单两名见证人必须为不同保管人。记录只追加。
 *
 * @param id           主键
 * @param inspectionId 关联巡检单ID
 * @param witnessId    见证保管人标识
 * @param sealResult   该见证人给出的封条结论
 * @param note         见证备注；null 表示未填写
 * @param createdAt    见证提交时间（Asia/Shanghai）
 */
public record InspectionWitness(
        Long id,
        Long inspectionId,
        String witnessId,
        SealResult sealResult,
        String note,
        LocalDateTime createdAt) {
}
