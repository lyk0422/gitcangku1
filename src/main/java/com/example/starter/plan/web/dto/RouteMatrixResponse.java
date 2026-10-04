package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 进路冲突矩阵视图：当前版本、全部已注册进路与规范化后的对称冲突对。
 */
public record RouteMatrixResponse(int version, List<String> routes,
                                  List<ConflictPairView> conflicts) {
}