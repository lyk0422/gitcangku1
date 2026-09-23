package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变墓碑恢复记录：对应 observation_recovery 表的一行，成功恢复后原子落库，永不修改。
 *
 * @param observationId    观测记录唯一标识
 * @param recoveredVersion 恢复后生成的新当前版本号（墓碑版本 + 1）
 * @param previousVersion  恢复前墓碑版本号
 * @param sourceVersion    恢复内容来源的历史非墓碑版本号（可跨代次）
 * @param generationBefore 恢复前合并代次
 * @param generationAfter  恢复后合并代次（= generationBefore + 1）
 * @param reason           恢复原因（非空）
 * @param requestId        生成该恢复记录的请求标识
 * @param recoveredAtUtc   恢复完成时刻（UTC）
 */
public record RecoveryRecord(
        String observationId,
        int recoveredVersion,
        int previousVersion,
        int sourceVersion,
        int generationBefore,
        int generationAfter,
        String reason,
        String requestId,
        Instant recoveredAtUtc) {
}
