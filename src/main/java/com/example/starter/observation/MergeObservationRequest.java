package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 离线提交（三方合并）请求：携带基线版本与三个可编辑字段的完整候选值。
 * 带量纲记录必须携带与创建时一致的量纲；跨量纲提交或不支持单位返回 400。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param baseVersion 离线修改所基于的基线版本号
 * @param location    观测地点候选值
 * @param reading     观测读数候选值，十进制字符串，最多三位小数（带量纲时为原始单位下的原值）
 * @param note        观测备注候选值
 * @param dimension   读数量纲：LENGTH / TEMPERATURE；必须与记录创建时的量纲一致
 * @param readingUnit 读数原始单位：m / cm / C / K，必须属于所给量纲
 * @param operator    操作者标识（可选，参与幂等身份）
 */
public record MergeObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @Size(max = 16) String dimension,
        @Size(max = 8) String readingUnit,
        @Size(max = 128) String operator) {

    /**
     * 旧无单位读数合并请求（不携带量纲、单位与操作者）。
     */
    public MergeObservationRequest(String requestId, Integer baseVersion,
                                   String location, String reading, String note) {
        this(requestId, baseVersion, location, reading, note, null, null, null);
    }
}
