package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 更新进路冲突矩阵请求：expectedVersion 必须与当前矩阵版本一致，成功版本加一。
 * requestId 为幂等键；operator 为操作者标识，计入幂等身份。
 * addConflicts / removeConflicts 为空列表表示不变更对应方向。
 */
public record UpdateRouteMatrixRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull List<@Valid ConflictPairRequest> addConflicts,
        @NotNull List<@Valid ConflictPairRequest> removeConflicts) {
}