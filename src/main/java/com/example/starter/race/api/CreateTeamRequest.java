package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 创建团队请求；members 为 3~5 个不同的已登记参赛号，同一选手最多属于一队。
 *
 * @param teamCode        团队代码，赛事内唯一
 * @param members         成员参赛号列表（3~5个，互不重复）；集合换序视为同一参数
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record CreateTeamRequest(
        @NotBlank String teamCode,
        @NotNull List<@NotBlank String> members,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
