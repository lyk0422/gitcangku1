package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量批准重分申请请求；任一申请不合法则整批失败（422），
 * 成功时在同一事务内迁移全部选手组别并重算来源与目标组别排名。
 *
 * @param operator        执行批准的操作者
 * @param applicationIds  待批准的申请ID集合；同一参赛者在批内仅允许出现一次
 * @param expectedVersion 客户端所见赛事版本
 * @param reclassKey      批次幂等键；指纹含操作者、赛事版本、规范化申请集合与目标组别，
 *                        同键成功重放完整排名结果，失败不占键
 */
public record ApproveReclassRequest(
        @NotBlank String operator,
        @NotEmpty List<@NotBlank String> applicationIds,
        @NotNull Integer expectedVersion,
        @NotBlank String reclassKey
) {
}