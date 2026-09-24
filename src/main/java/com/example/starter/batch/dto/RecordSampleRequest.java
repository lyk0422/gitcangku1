package com.example.starter.batch.dto;

import com.example.starter.batch.DefectGrade;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 逐件样本登记请求。sampleNo 为样本序号 1～样本量，同一计划内不可重复；
 * grade 为 null 表示合格件，否则为缺陷等级 CRITICAL/MAJOR/MINOR；description 非空。
 */
public record RecordSampleRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "sampleNo 不能为空")
        @Min(value = 1, message = "样本序号必须从 1 开始")
        @Max(value = 200, message = "样本序号不能超过样本量上限 200")
        Integer sampleNo,
        DefectGrade grade,
        @NotBlank(message = "登记描述 description 不能为空") String description
) {
}
