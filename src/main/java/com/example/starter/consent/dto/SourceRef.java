package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 派生来源引用：按主体、代次、记录键钉住一条数值或派生记录，不按同名键重解析。
 *
 * @param subjectKey 来源主体标识（合成字符串）
 * @param epoch      来源记录所属研究授权代次，从 1 开始
 * @param recordKey  来源记录键
 */
public record SourceRef(
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull @Min(1) Integer epoch,
        @NotBlank @Size(max = 128) String recordKey) {
}
