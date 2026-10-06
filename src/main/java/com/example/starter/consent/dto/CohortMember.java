package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 群体汇总成员：主体及其明确的当前授权代次与记录键。
 *
 * @param subjectKey 主体标识（合成字符串），一次汇总内互不重复
 * @param epoch      主体当前授权代次，从 1 开始；已撤回或被新代替代则整体 410
 * @param recordKey  记录键
 */
public record CohortMember(
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull @Min(1) Integer epoch,
        @NotBlank @Size(max = 128) String recordKey) {
}
