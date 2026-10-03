package com.example.starter.batch;

import java.time.Instant;

/**
 * 批次视图。
 *
 * @param id               批次ID
 * @param batchNo          批次号，全局唯一
 * @param programVersionId 入炉时锁定的程序版本
 * @param status           状态：FIRING/RELEASED/REJECTED
 * @param refireOfId       复烧来源批次，NULL表示非复烧
 * @param createdAt        创建时间，UTC
 */
public record BatchInfo(long id, String batchNo, long programVersionId, String status,
                        Long refireOfId, Instant createdAt) {

    public static final String FIRING = "FIRING";
    public static final String RELEASED = "RELEASED";
    public static final String REJECTED = "REJECTED";
}