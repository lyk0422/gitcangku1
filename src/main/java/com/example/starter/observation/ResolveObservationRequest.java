package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 冲突解决提交请求：普通合并返回 409 后，客户端携带完整候选值与每个冲突字段的人工选择。
 *
 * @param resolutionId           全局唯一解决标识（解决记录去重键）
 * @param requestId              全局唯一请求标识（幂等去重键）
 * @param baseVersion            离线修改所基于的基线版本号
 * @param expectedCurrentVersion 客户端期望的当前版本号，不匹配返回 409
 * @param location               观测地点候选值（完整提交）
 * @param reading                观测读数候选值，十进制字符串，最多三位小数
 * @param note                   观测备注候选值
 * @param selections             各冲突字段的人工选择：字段名 -> CURRENT 或 CANDIDATE；
 *                               必须恰好覆盖服务端重算后仍冲突的字段
 * @param operator               操作者标识
 */
public record ResolveObservationRequest(
        @NotBlank @Size(max = 128) String resolutionId,
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Min(1) Integer expectedCurrentVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @NotNull Map<String, String> selections,
        @NotBlank @Size(max = 128) String operator) {
}
