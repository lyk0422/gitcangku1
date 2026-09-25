package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 接力配置请求：把 OPEN 赛事标记为接力并登记各队棒次选手，配置后不可修改。
 *
 * @param legCount        棒次数，取值2~8
 * @param exchangeLimitMs 交接区用时上限（毫秒，1~10000），超过即判犯规
 * @param teams           队伍及其棒次选手名单；runners 下标即棒次（从1开始），顺序固定
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RelayConfigRequest(
        @NotNull @Min(2) @Max(8) Integer legCount,
        @NotNull @Min(1) @Max(10000) Long exchangeLimitMs,
        @NotEmpty @Valid List<TeamRunners> teams,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {

    /**
     * 一支队伍的棒次选手名单；runners 第 i 个元素为第 i+1 棒选手，同队同棒次仅一人。
     *
     * @param teamKey 队伍标识，同一赛事内唯一
     * @param runners 按棒次顺序排列的选手标识，数量须等于棒次数
     */
    public record TeamRunners(
            @NotBlank String teamKey,
            @NotEmpty List<@NotBlank String> runners
    ) {
    }
}
