package com.example.starter.firmware.api;

/**
 * 兼容矩阵登记响应：新矩阵版本与本次登记条数。
 */
public record MatrixRegisterResponse(int matrixVersion, int requirementCount, int dependencyCount,
                                     String registeredAtUtc) {
}
