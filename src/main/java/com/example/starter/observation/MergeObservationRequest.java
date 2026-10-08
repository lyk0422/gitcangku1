package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 离线提交（三方合并）请求：携带基线版本与可编辑字段的完整候选值。
 *
 * <p>FIELD 观测必须携带 location/reading/note 候选值；SECTION 观测必须携带
 * sectionNo/lateralMm/elevationMm 完整版本组候选值。类型相关的必填校验在服务层按当前观测类型执行。
 *
 * @param requestId    全局唯一请求标识（幂等去重键）
 * @param baseVersion  离线修改所基于的基线版本号
 * @param location     观测地点候选值（FIELD）
 * @param reading      观测读数候选值，十进制字符串，最多三位小数（FIELD）
 * @param note         观测备注候选值（FIELD）
 * @param sectionNo    断面号候选值（SECTION 版本组）
 * @param lateralMm    横向坐标候选值，整数毫米（SECTION 版本组）
 * @param elevationMm  高程候选值，整数毫米（SECTION 版本组）
 */
public record MergeObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
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
    public MergeObservationRequest(String requestId, Integer baseVersion,
                                   String location, String reading, String note) {
        this(requestId, baseVersion, location, reading, note, null, null, null);
    }
}
