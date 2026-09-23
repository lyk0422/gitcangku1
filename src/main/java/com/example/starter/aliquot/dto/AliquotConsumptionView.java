package com.example.starter.aliquot.dto;

import com.example.starter.aliquot.AliquotStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * “母样-数量-子样”映射视图（耗用成功后存在，不可变）。
 *
 * @param sampleKey        被耗用的母样业务键
 * @param quantity         本次耗用数量
 * @param childEvidenceKey 生成的 SEALED 子样证物键
 */
public record AliquotConsumptionView(
        String sampleKey,
        long quantity,
        String childEvidenceKey) {

    /**
     * 联合取样单完整视图。
     *
     * @param aliquotKey      取样单业务键
     * @param applicantId     申请人
     * @param status          取样单状态
     * @param version         申请版本
     * @param items           母样取用量明细（含申请时母样版本快照）
     * @param consumptions    耗用成功后的“母样-数量-子样”映射；未耗用为空
     * @param reviews         审核历史（按顺序，只读）
     * @param createdAt       申请时间
     * @param updatedAt       最近变更时间
     */
    public record Detail(
            String aliquotKey,
            String applicantId,
            AliquotStatus status,
            long version,
            List<ItemView> items,
            List<AliquotConsumptionView> consumptions,
            List<AliquotReviewView> reviews,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 母样明细视图。
     *
     * @param sampleKey     母样业务键
     * @param quantity      取用量
     * @param sampleVersion 申请时母样版本快照
     */
    public record ItemView(String sampleKey, long quantity, long sampleVersion) {
    }
}
