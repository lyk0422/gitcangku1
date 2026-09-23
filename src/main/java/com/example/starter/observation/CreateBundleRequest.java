package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 建立关联观测簇请求：把 2~50 条属于同一 surveyId 的观测按 bundleKey 关联，并声明必须一致的字段集合。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param bundleKey        关联簇唯一标识（业务唯一键）
 * @param surveyId         簇内全部观测必须所属的调查（survey）唯一标识
 * @param consistentFields 声明必须一致的字段名集合，取值 location/reading/note；允许为空集合
 * @param members          成员列表（2~50 条），观测标识不得重复，顺序不影响同参判定
 * @param operator         建簇审核员标识
 */
public record CreateBundleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String bundleKey,
        @NotBlank @Size(max = 64) String surveyId,
        @NotNull List<String> consistentFields,
        @NotEmpty @Size(min = 2, max = 50) @Valid List<BundleMemberItem> members,
        @NotBlank @Size(max = 128) String operator) {
}
