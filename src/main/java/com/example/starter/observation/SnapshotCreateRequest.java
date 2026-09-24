package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 冻结快照创建请求：提交 snapshotKey、目标 UTC 时刻与 1～50 个 observationId。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）：同键同参重放首次快照，异参 409，失败不占键
 * @param snapshotKey    全局唯一冻结快照标识；跨 requestId 复用返回 409
 * @param targetTimeUtc  快照目标 UTC 时刻（ISO-8601）；晚于服务端当前时刻返回 400
 * @param observationIds 待固化的观测记录标识集合，允许重复与乱序（按升序去重后处理，换序视为同参），数量 1～50
 */
public record SnapshotCreateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String snapshotKey,
        @NotNull Instant targetTimeUtc,
        @NotEmpty @Size(min = 1, max = 50) List<@NotBlank @Size(max = 64) String> observationIds) {
}
