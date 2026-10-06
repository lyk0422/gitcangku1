package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 分层样方密度估计请求：2 至 5 层，每层正整数总体面积与 1 至 5 个不同观测的当前版本，
 * 观测不得跨层重复，单次总计最多 20 个观测。层与成员的顺序不影响参数同一性。
 *
 * @param requestId  全局唯一请求标识（幂等去重键）
 * @param estimateId 计算成果唯一标识（资源标识，同参重放、异参 409）
 * @param operator   操作者标识（非空）
 * @param layers     估计层列表（2 至 5 层）
 */
public record EstimateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String estimateId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 2, max = 5) List<@Valid LayerRequest> layers) {

    /**
     * 估计层：正整数总体面积与 1 至 5 个不同观测成员。
     *
     * @param totalArea 该层总体面积（正整数，单位平方米）
     * @param members   该层引用的观测成员（1 至 5 个，层内 observationId 不重复）
     */
    public record LayerRequest(
            @NotNull @Min(1) Integer totalArea,
            @NotNull @Size(min = 1, max = 5) List<@Valid MemberRequest> members) {
    }

    /**
     * 层成员：引用观测记录的当前版本。
     *
     * @param observationId   观测记录唯一标识
     * @param expectedVersion 客户端期望的当前版本号，与服务端当前版本不一致时整次 409
     */
    public record MemberRequest(
            @NotBlank @Size(max = 64) String observationId,
            @NotNull @Min(1) Integer expectedVersion) {
    }
}
