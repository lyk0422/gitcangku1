package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建观测记录请求。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param observationId    观测记录唯一标识
 * @param location         观测地点
 * @param reading          观测读数，非负十进制字符串，最多三位小数
 * @param readingQualifier 读数限定符：EXACT/LT/GT（大小写不敏感）；缺省为 EXACT，与读数组成不可拆分的一组
 * @param note             观测备注
 */
public record CreateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "\\d+(\\.\\d{1,3})?", message = "reading must be a non-negative decimal string with at most 3 fraction digits")
        String reading,
        @Pattern(regexp = "(?i)EXACT|LT|GT", message = "readingQualifier must be one of EXACT/LT/GT")
        String readingQualifier,
        @NotNull @Size(max = 1024) String note) {

    /**
     * 兼容旧普通读数的构造方式：不带限定符，默认按 EXACT 处理。
     */
    public CreateObservationRequest(String requestId, String observationId,
                                    String location, String reading, String note) {
        this(requestId, observationId, location, reading, null, note);
    }
}