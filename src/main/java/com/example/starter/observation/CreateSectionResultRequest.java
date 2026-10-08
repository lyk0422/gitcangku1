package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建断面成果请求：引用同断面 2 至 10 条当前版本断面点（按给出顺序，横向必须严格递增）
 * 与整数水面高程 H，计算并冻结水域面积。
 *
 * @param requestId    全局唯一请求标识（幂等去重键）
 * @param resultId     断面成果唯一标识
 * @param operator     操作者标识（非空）
 * @param sources      有序源断面点引用（观测标识 + 期望当前版本），2 至 10 条
 * @param waterLevelMm 整数水面高程 H，单位毫米
 */
public record CreateSectionResultRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String resultId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 2, max = 10) List<@Valid SourceRef> sources,
        @NotNull Long waterLevelMm) {

    /**
     * 源断面点引用：观测标识与该次成果所基于的期望当前版本。
     *
     * @param observationId   源观测记录唯一标识
     * @param expectedVersion 期望的源当前版本号，不匹配返回 409
     */
    public record SourceRef(
            @NotBlank @Size(max = 64) String observationId,
            @NotNull @Min(1) Integer expectedVersion) {
    }
}
