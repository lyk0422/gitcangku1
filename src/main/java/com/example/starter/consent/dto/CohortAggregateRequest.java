package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 最小群体汇总请求：仅研究用途，输入 3-10 个不同主体及其明确当前代次与记录键，
 * 每主体一项；集合换序视为相同参数。成功只返回人数与总和，不返回逐主体值或名单。
 *
 * @param requestId 幂等请求标识
 * @param purpose   用途，仅允许 RESEARCH 研究
 * @param members   群体成员列表，3-10 项，每主体一项
 */
public record CohortAggregateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Purpose purpose,
        @NotNull @Size(min = 3, max = 10) List<@Valid Member> members) {

    /**
     * 群体成员：指定主体、期望代次（须仍为当前有效代次）与记录键。
     *
     * @param subjectKey 主体标识（合成字符串）
     * @param epoch      期望的授权代次，从 1 开始；已撤回或被新代替代则整体 410
     * @param recordKey  记录键
     */
    public record Member(
            @NotBlank @Size(max = 128) String subjectKey,
            @NotNull @Min(1) Integer epoch,
            @NotBlank @Size(max = 128) String recordKey) {
    }
}
