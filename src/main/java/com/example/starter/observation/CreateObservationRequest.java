package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建观测记录请求。量纲在创建时固定：dimension 与 readingUnit 必须同时提供或同时缺省，
 * 缺省表示旧无单位读数（后续不自动推断量纲）。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 观测记录唯一标识
 * @param location      观测地点
 * @param reading       观测读数，十进制字符串，最多三位小数（带量纲时为原始单位下的原值）
 * @param note          观测备注
 * @param dimension     读数量纲：LENGTH / TEMPERATURE；null 表示旧无单位读数
 * @param readingUnit   读数原始单位：m / cm / C / K，必须属于所给量纲；null 表示无单位
 * @param operator      操作者标识（可选，参与幂等身份）
 */
public record CreateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @Size(max = 16) String dimension,
        @Size(max = 8) String readingUnit,
        @Size(max = 128) String operator) {

    /**
     * 旧无单位读数创建请求（不携带量纲、单位与操作者）。
     */
    public CreateObservationRequest(String requestId, String observationId,
                                    String location, String reading, String note) {
        this(requestId, observationId, location, reading, note, null, null, null);
    }
}
