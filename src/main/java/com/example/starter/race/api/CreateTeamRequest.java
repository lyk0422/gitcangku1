package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建团队请求：OPEN 赛事内新建一个 teamCode，整体配置3~5个不同的已登记参赛号。
 *
 * @param teamCode        团队代码，赛事内唯一（长度不超过64）
 * @param memberBibs      3~5个互不重复且已登记的参赛号（集合，换序视为同一参数；单个参赛号长度不超过64）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record CreateTeamRequest(
        @NotBlank @Size(max = 64) String teamCode,
        @NotEmpty @Size(min = 3, max = 5) List<@NotBlank @Size(max = 64) String> memberBibs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
