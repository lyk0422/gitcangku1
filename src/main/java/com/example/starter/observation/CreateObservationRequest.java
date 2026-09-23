package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建观测记录请求。
 *
 * <p>siteKey / type / observedAt 为重复观测簇归并的分组与时间维度，可省略（历史/不参与归并的观测）；
 * 三者齐备且记录 ACTIVE、未归并时，才可能出现在簇候选预览中。deviceId 仅作为归并证据冻结项，可省略。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 观测记录唯一标识（记录键）
 * @param location      观测地点
 * @param reading       观测读数，十进制字符串，最多三位小数
 * @param note          观测备注
 * @param siteKey       站点键（簇分组维度，可选）
 * @param type          观测类型（簇分组维度，可选）
 * @param observedAt    观测发生时刻（UTC，ISO-8601；簇时间范围重算依据，可选）
 * @param deviceId      采集设备标识（归并证据冻结项，可选）
 */
public record CreateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @Size(max = 64) String siteKey,
        @Size(max = 64) String type,
        Instant observedAt,
        @Size(max = 128) String deviceId) {

    /**
     * 兼容不参与簇归并观测的便捷构造：不带分组维度与设备标识。
     */
    public CreateObservationRequest(String requestId, String observationId, String location,
                                    String reading, String note) {
        this(requestId, observationId, location, reading, note, null, null, null, null);
    }
}
