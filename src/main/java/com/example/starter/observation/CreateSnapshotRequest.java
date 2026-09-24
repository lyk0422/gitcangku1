package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建冻结快照请求：提交 snapshotKey、目标 UTC 时刻与 1～50 个观测记录标识。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param snapshotKey    全局唯一快照标识；异参复用返回 409
 * @param targetTimeUtc  快照目标 UTC 时刻；晚于服务端当前时刻返回 400
 * @param observationIds 观测记录标识集合，1～50 个；集合换序视为同参；任一 ID 在任何时刻都不存在则整次 404
 */
public record CreateSnapshotRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String snapshotKey,
        @NotNull Instant targetTimeUtc,
        @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 64) String> observationIds) {
}
