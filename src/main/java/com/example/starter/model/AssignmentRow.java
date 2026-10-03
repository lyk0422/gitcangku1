package com.example.starter.model;

import java.time.Instant;

/**
 * 指派行记录，时间均为 UTC。
 *
 * @param id                     主键
 * @param segmentId              航段ID
 * @param pilotId                引航员ID
 * @param requestId              指派幂等请求键
 * @param requestFingerprint     指派请求参数指纹
 * @param status                 指派状态
 * @param boardedAtUtc           实际登轮时间（UTC），未登轮为 null
 * @param disembarkedAtUtc       实际离轮时间（UTC），未离轮为 null
 * @param boardRequestId         登轮幂等请求键
 * @param boardRequestFingerprint 登轮请求参数指纹
 * @param createdAtUtc           指派创建时间（UTC）
 * @param releasedAtUtc          释放时间（UTC），未释放为 null
 * @param releaseReason          释放原因
 */
public record AssignmentRow(long id, long segmentId, long pilotId, String requestId,
                            String requestFingerprint, AssignmentStatus status,
                            Instant boardedAtUtc, Instant disembarkedAtUtc,
                            String boardRequestId, String boardRequestFingerprint,
                            Instant createdAtUtc, Instant releasedAtUtc, String releaseReason) {
}