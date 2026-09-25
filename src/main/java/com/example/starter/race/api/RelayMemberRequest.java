package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接力队伍某一棒次的登记选手。
 *
 * @param legNo 棒次序号（1~棒次数），同队同棒次仅一人
 * @param bib   该棒次选手参赛号
 */
public record RelayMemberRequest(
        @NotNull @Min(1) @Max(8) Integer legNo,
        @NotBlank String bib
) {
}
