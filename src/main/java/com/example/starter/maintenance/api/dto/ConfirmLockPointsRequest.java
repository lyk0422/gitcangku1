package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

/**
 * 维护角色确认锁定点请求：同一角色对同一锁定点只能确认一次（重复确认返回 422）；
 * 当所有锁定点均经两名不同角色确认后，隔离令整体生效。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param role       维护角色：ELECTRICIAN 电气 / MECHANIC 机械（其他值 400）
 * @param operator   确认操作人（确认时固化）
 * @param pointIds   本次确认的锁定点标识集合；须为隔离令现存锁定点，不能为空，重复元素 422
 */
public record ConfirmLockPointsRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "ELECTRICIAN|MECHANIC", message = "必须为 ELECTRICIAN 或 MECHANIC") String role,
        @NotBlank String operator,
        @NotEmpty @Valid List<@NotBlank String> pointIds) {
}
