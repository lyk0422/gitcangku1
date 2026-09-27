package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交组别重分申请请求。
 *
 * @param bib             申请参赛者参赛号
 * @param targetCategory  目标组别代码，必须已存在且未封榜
 * @param reason          申请理由（1~512字符）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键，兼作申请ID）
 */
public record ReclassApplyRequest(
        @NotBlank String bib,
        @NotBlank String targetCategory,
        @NotBlank @Size(max = 512) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
