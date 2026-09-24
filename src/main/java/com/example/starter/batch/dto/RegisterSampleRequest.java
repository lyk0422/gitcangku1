package com.example.starter.batch.dto;

import com.example.starter.batch.SampleResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 逐件样本登记请求。sampleIndex 样本序号 1～200（业务上还须 1～样本量，由服务层校验）且同计划内不重复；
 * result 为 QUALIFIED/CRITICAL/MAJOR/MINOR；description 为非空描述。
 */
public record RegisterSampleRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "sampleIndex 不能为空")
        @Min(value = 1, message = "样本序号最小为 1")
        @Max(value = 200, message = "样本序号最大为 200")
        Integer sampleIndex,
        @NotNull(message = "result 不能为空") SampleResult result,
        @NotBlank(message = "描述不能为空") String description
) {
}
