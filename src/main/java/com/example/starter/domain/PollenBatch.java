package com.example.starter.domain;

import java.time.Instant;

/**
 * 花粉批次：授粉操作的花粉来源。
 *
 * @param id 主键
 * @param batchNo 花粉批号，全局唯一
 * @param status 批次状态：ACTIVE=可用，DISABLED=已停用（停用后禁止用于新授粉操作）
 * @param createdAt 记录创建时刻（UTC）
 */
public record PollenBatch(long id, String batchNo, BatchStatus status, Instant createdAt) {

    /**
     * 批次状态枚举。
     */
    public enum BatchStatus {
        /** 可用。 */
        ACTIVE,
        /** 已停用，禁止用于新授粉操作。 */
        DISABLED
    }
}