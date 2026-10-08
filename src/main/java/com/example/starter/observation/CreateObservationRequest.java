package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建观测记录请求。
 *
 * <p>observationType 缺省为 FIELD。FIELD 类型必须携带 location/reading/note 且不得携带断面字段；
 * SECTION 类型必须携带 sectionNo/lateralMm/elevationMm（完整版本组）且不得携带 FIELD 字段。
 * 类型相关的必填校验在服务层按类型执行。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param observationId   观测记录唯一标识
 * @param observationType 观测类型：FIELD / SECTION，缺省 FIELD
 * @param location        观测地点（FIELD 必填）
 * @param reading         观测读数，十进制字符串，最多三位小数（FIELD 必填）
 * @param note            观测备注（FIELD 必填）
 * @param sectionNo       断面号（SECTION 必填）
 * @param lateralMm       横向坐标，整数毫米（SECTION 必填）
 * @param elevationMm     高程，整数毫米（SECTION 必填）
 */
public record CreateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @Size(max = 16) String observationType,
        @Size(max = 512) String location,
        @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @Size(max = 1024) String note,
        @Size(max = 64) String sectionNo,
        Integer lateralMm,
        Integer elevationMm) {

    /**
     * FIELD 类型便捷构造（不带断面字段）。
     */
    public CreateObservationRequest(String requestId, String observationId,
                                    String location, String reading, String note) {
        this(requestId, observationId, null, location, reading, note, null, null, null);
    }
}
