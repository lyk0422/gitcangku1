package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 冲突解决请求：在普通三方合并收到冲突后，提交三个字段的完整候选值及对每个冲突字段的人工选择。
 *
 * @param requestId              全局唯一请求标识（幂等去重键）
 * @param resolutionId           全局唯一解决记录标识
 * @param baseVersion            离线修改所基于的基线版本号（服务端据此重新读取基线重算冲突）
 * @param expectedCurrentVersion 客户端预期的解决前当前版本号，不匹配返回 409
 * @param location               观测地点候选完整值
 * @param reading                观测读数候选完整值，十进制字符串，最多三位小数
 * @param note                   观测备注候选完整值
 * @param selections             冲突字段选择，键为字段名，值为 CURRENT 或 CANDIDATE；必须恰好覆盖本次仍冲突的字段
 * @param operator               操作者标识
 */
public record ResolveConflictRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String resolutionId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Min(1) Integer expectedCurrentVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @NotNull Map<String, String> selections,
        @NotBlank @Size(max = 128) String operator) {
}
