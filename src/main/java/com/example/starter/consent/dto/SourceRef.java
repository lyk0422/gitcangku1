package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 派生来源引用：按“主体＋代次＋记录键”精确定位一条历史研究记录，
 * 保存后不按当前同名键替换。
 *
 * @param subjectKey 来源主体标识（合成字符串）
 * @param epoch      来源主体授权代次，从 1 开始；必须当前有效
 * @param recordKey  来源记录键
 */
public record SourceRef(
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull @Min(1) Integer epoch,
        @NotBlank @Size(max = 128) String recordKey) {
}
