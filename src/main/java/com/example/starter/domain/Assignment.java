package com.example.starter.domain;

import java.time.Instant;

/**
 * 引航员指派及其登轮、离轮、撤销事实。
 *
 * @param id                主键
 * @param requestId         指派幂等请求键，唯一；同参重放返回首次结果，异参返回 409
 * @param segmentId         航段申请主键
 * @param pilotId           引航员主键
 * @param status            指派状态
 * @param estStartUtc       指派时锁定的预计开始时刻，UTC，含
 * @param estEndUtc         指派时锁定的预计结束时刻，UTC，不含
 * @param boardedAtUtc      实际登轮时刻，UTC，未登轮为 null；登轮后占用延续到实际离轮
 * @param departedAtUtc     实际离轮时刻，UTC，未离轮为 null；离轮后释放占用
 * @param revokedAtUtc      撤销时刻，UTC，未撤销为 null；仅未登轮可撤销
 * @param boardingRequestId 登轮确认幂等请求键，未登轮为 null
 * @param createdAtUtc      指派创建时刻，UTC
 */
public record Assignment(long id, String requestId, long segmentId, long pilotId, AssignmentStatus status,
                         Instant estStartUtc, Instant estEndUtc, Instant boardedAtUtc, Instant departedAtUtc,
                         Instant revokedAtUtc, String boardingRequestId, Instant createdAtUtc) {
}