package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 大幅读数修订见证申请。
 *
 * @param equipmentId                所属设备标识
 * @param readingId                  被修订读数标识
 * @param requestId                  提交修订的请求 requestId（幂等键）
 * @param baseRevisionNo             提交时读数当前有效修订号（见证版本基准）
 * @param proposedRevisionNo         生效后将占用的读数修订号（申请提交时预分配）
 * @param currentCumulativeMinutes   提交时最后一条有效读数累计工时快照（分钟）
 * @param proposedCumulativeMinutes  申请的新读数累计工时（分钟）
 * @param deltaMinutes               差额（分钟）= 申请值 - 提交时有效值，保留正负号
 * @param thresholdMinutes           提交时设备当前见证阈值快照（分钟），不随后续配置变化
 * @param submitterId                提交修订的维护人标识
 * @param witnessId                  见证维护人标识；PENDING/SUPERSEDED 时为 null
 * @param status                     状态：PENDING 待确认、EFFECTIVE 已见证生效、SUPERSEDED 已被新修订取代
 * @param expectedVersion            提交时设备期望版本（乐观锁快照）
 * @param createdAt                  申请提交时刻（UTC）
 * @param witnessedAt                见证生效时刻（UTC）；PENDING/SUPERSEDED 时为 null
 */
public record RevisionRequest(
        String equipmentId,
        String readingId,
        String requestId,
        int baseRevisionNo,
        int proposedRevisionNo,
        long currentCumulativeMinutes,
        long proposedCumulativeMinutes,
        long deltaMinutes,
        long thresholdMinutes,
        String submitterId,
        String witnessId,
        String status,
        long expectedVersion,
        Instant createdAt,
        Instant witnessedAt) {

    /** 待确认：已提交超阈值修订，等待另一名维护人见证。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 已见证生效：见证人确认同一读数版本，申请值已成为有效读数。 */
    public static final String STATUS_EFFECTIVE = "EFFECTIVE";

    /** 已被取代：读数在见证前又产生了新的直接生效修订或新申请，本申请不可再见证。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
}
