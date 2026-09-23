package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交处罚申诉请求。
 *
 * <p>参赛者只能针对自己一条已生效且未被申诉的处罚，在 finishAt（最近计时修订时间）后30分钟内提交。
 * 受理不改变榜单版本，仅冻结处罚、原始/净成绩、分段判定与当时榜单版本。
 *
 * @param appealKey      申诉键，全局唯一
 * @param penaltyVersion 客户端所见被申诉处罚版本；与当前不一致返回409
 * @param reason         申诉理由
 * @param requestId      全局唯一请求ID（幂等键）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitAppealRequest(
        @NotBlank String appealKey,
        @NotNull Integer penaltyVersion,
        @NotBlank @Size(max = 1000) String reason,
        @NotBlank String requestId
) {
}
