package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量批准重分申请请求；任一申请不合法则整批失败（422），不产生任何迁移。
 *
 * @param reclassKey      批准批次键（幂等键）：指纹含操作者、赛事版本、规范化申请集合与目标组别；
 *                        同键成功重放完整排名结果，失败不占键
 * @param operator        批准操作者
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestIds      待批准的申请ID集合（同一参赛者在一批中仅允许出现一次）
 */
public record ApproveReclassRequest(
        @NotBlank String reclassKey,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotEmpty List<@NotBlank String> requestIds
) {
}
