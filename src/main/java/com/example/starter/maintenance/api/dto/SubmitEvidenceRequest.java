package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交保养证据包请求：整体替换当前周期证据并生成递增版本。
 * 先校验完整最终包，任一检查项或材料不合法则整次回滚。
 *
 * @param requestId       全局唯一请求标识（幂等键）
 * @param expectedVersion 设备期望版本号，与当前版本不一致时返回 409
 * @param submittedBy     提交人（维护人员）；提交人不能确认本人提交的完工
 * @param itemResults     检查项结果列表（可为空列表，表示尚未填写任何检查项）
 * @param materials       材料批次摘要列表，至少一条
 */
public record SubmitEvidenceRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String submittedBy,
        @NotNull List<@Valid ItemResultInput> itemResults,
        @NotNull List<@Valid MaterialSummaryInput> materials) {
}
