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
 * @param reading     观测读数候选值，十进制字符串，最多三位小数；带量纲记录按 readingUnit 解释
 * @param note        观测备注候选值
 * @param readingUnit 读数原始单位：长度 m/cm，温度 C/K；带量纲记录必填且不得跨量纲，旧无单位记录不得提供
 * @param operator    操作者标识（可选，参与幂等指纹）
 */
public record MergeObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @Size(max = 8) String readingUnit,
        @Size(max = 128) String operator) {

    /**
     * 旧无单位读数的合并请求：单位与操作者均为 null。
     */
    public MergeObservationRequest(String requestId, Integer baseVersion, String location,
                                   String reading, String note) {
        this(requestId, baseVersion, location, reading, note, null, null);
    }
}
