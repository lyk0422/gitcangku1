package com.example.starter.cue.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提示点执行回报请求。
 *
 * @param requestId  幂等请求 ID；同参重放返回原回报，异参 409
 * @param seq        回报的提示点序号，必须等于下一待执行序号
 * @param operator   操作员
 * @param result     SUCCESS 或 FAILED
 * @param failReason 失败原因，FAILED 时必填
 */
public record ReportRequest(@NotBlank String requestId,
                            @NotNull Integer seq,
                            @NotBlank String operator,
                            @NotBlank String result,
                            String failReason) {
}