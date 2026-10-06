package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记航线版本能量档案请求。档案绑定当前航线版本并随之冻结；
 * 档案变化必须先通过航线修订生成新版本，再为新版本登记。
 *
 * <p>所有能量值单位为标准能量单位；单项上限 1e12，配合最多 10 段的限制，
 * 逐点累计求和不会溢出长整型。</p>
 *
 * @param routeId             航线唯一标识
 * @param expectedVersion     期望的当前航线版本（版本不匹配返回 409）
 * @param availableEnergy     出发可用能量，非负
 * @param reserveEnergy       必留余量，非负，每个航点判定都计入
 * @param segmentConsumptions 每段正整数耗能，条数必须等于已登记坐标段数（航点数 - 1），最多 10 段
 * @param diversionEnergies   每个航点备降选项的非负整数额外耗能，必须所有航点齐全（条数等于航点数）
 * @param operator            登记操作者，非空
 * @param requestId           写操作全局唯一请求标识，用于幂等重放
 */
public record EnergyProfileRegisterRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Min(0) @Max(1_000_000_000_000L) Long availableEnergy,
        @NotNull @Min(0) @Max(1_000_000_000_000L) Long reserveEnergy,
        @NotNull @Size(min = 1, max = 10) List<@NotNull @Min(1) @Max(1_000_000_000_000L) Long> segmentConsumptions,
        @NotNull @Size(min = 2, max = 11) List<@NotNull @Min(0) @Max(1_000_000_000_000L) Long> diversionEnergies,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
