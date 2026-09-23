package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 建立关联观测簇请求：把同一 surveyId 的 2~50 条观测按 bundleKey 关联并声明必须一致的字段集。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param bundleKey        关联观测簇唯一业务标识
 * @param surveyId         簇所属调查问卷标识；成员观测未声明 surveyId 时归属为该值
 * @param observationIds   成员观测标识列表（2~50 条，去重；允许含墓碑观测作为待恢复项）
 * @param consistentFields 要求簇内一致的字段名列表（location/reading/note 子集）
 * @param operator         建簇审核员标识
 */
public record CreateBundleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String bundleKey,
        @NotBlank @Size(max = 64) String surveyId,
        @NotEmpty @Size(min = 2, max = 50) List<@NotBlank @Size(max = 64) String> observationIds,
        @NotNull List<@NotBlank String> consistentFields,
        @NotBlank @Size(max = 128) String operator) {
}
