package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 离线提交（三方合并）请求：携带基线版本与三个可编辑字段的完整候选值。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param baseVersion 离线修改所基于的基线版本号
 * @param location    观测地点候选值
 * @param reading     观测读数候选值，十进制字符串，最多三位小数
 * @param note        观测备注候选值
 */
public record MergeObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}
