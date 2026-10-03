package com.example.starter.well;

import java.time.Instant;

/**
 * 运行模式切换申请。
 *
 * @param id 申请主键
 * @param wellNo 井号
 * @param requestId 幂等请求编号，同井唯一
 * @param payloadHash 请求参数摘要
 * @param fromMode 申请时当前模式：INJECTION/PRODUCTION
 * @param toMode 目标模式：INJECTION/PRODUCTION
 * @param status 状态：PENDING=待审核，APPROVED=已批准，REJECTED=已拒绝
 * @param rejectReason 拒绝原因，未拒绝为 null
 * @param newSegmentStart 批准后新运行段起点（UTC），未批准为 null
 * @param createdAt 申请创建时刻（UTC）
 * @param decidedAt 审核决定时刻（UTC），待审核为 null
 */
public record SwitchRequest(long id, String wellNo, String requestId, String payloadHash,
                            String fromMode, String toMode, String status, String rejectReason,
                            Instant newSegmentStart, Instant createdAt, Instant decidedAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
}