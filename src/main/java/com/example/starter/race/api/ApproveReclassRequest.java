package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量批准重分申请请求；任一申请不合法则整批拒绝（422/409），不产生部分迁移。
 *
 * <p>reclassKey 为批次幂等键：指纹含操作者、赛事版本、规范化申请集合与目标组别；
 * 同键成功重放完整排名结果，业务失败不占键。
 *
 * @param reclassKey      批次幂等键（操作者维度唯一）
 * @param operator        操作者标识
 * @param expectedVersion 客户端所见赛事版本
 * @param applicationIds  待批准申请ID集合（批内同一选手仅允许出现一次）
 */
public record ApproveReclassRequest(
        @NotBlank String reclassKey,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotEmpty List<@NotBlank String> applicationIds
) {
}
