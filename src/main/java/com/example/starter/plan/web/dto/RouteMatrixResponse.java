package com.example.starter.plan.web.dto;

import com.example.starter.plan.model.RoutePair;
import java.util.List;

/**
 * 进路冲突矩阵视图：当前版本与全部对称冲突对（按字典序规范化存储）。
 */
public record RouteMatrixResponse(long version, List<RoutePair> conflicts) {
}