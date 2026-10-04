package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 进路冲突矩阵更新请求。requestId 为幂等键；operator 为操作者标识；
 * expectedVersion 必须与当前矩阵版本一致；addPairs/removePairs 省略时视为空。
 */
public record UpdateRouteMatrixRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Long expectedVersion,
        List<@Valid RoutePairRequest> addPairs,
        List<@Valid RoutePairRequest> removePairs) {

    /**
     * 空清单归一为空集，便于后续统一处理。
     */
    public UpdateRouteMatrixRequest {
        addPairs = addPairs == null ? List.of() : List.copyOf(addPairs);
        removePairs = removePairs == null ? List.of() : List.copyOf(removePairs);
    }
}