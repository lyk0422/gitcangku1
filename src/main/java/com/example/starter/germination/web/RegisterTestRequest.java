package com.example.starter.germination.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记一次发芽率检测（取样检测/复检追加）请求。
 *
 * <p>样本数 100-400、发芽数不超过样本数等业务范围规则由服务层
 * {@link com.example.starter.germination.service.GerminationRules} 统一判定，
 * 以返回明确的业务拒绝原因（422）；此处仅做非空与非负的基础校验。</p>
 *
 * @param testNo           检测编号，全库唯一
 * @param batchNo          所属种子批次编号
 * @param sampleCount      样本数，单位粒，业务合法区间 100-400
 * @param germinatedCount  发芽粒数，单位粒，业务上不得超过样本数
 * @param requestId        幂等请求号；同号同参重放返回首次结果，异参返回 409
 */
public record RegisterTestRequest(@NotBlank String testNo,
                                  @NotBlank String batchNo,
                                  @NotNull @Min(0) Integer sampleCount,
                                  @NotNull @Min(0) Integer germinatedCount,
                                  @NotBlank String requestId) {
}
