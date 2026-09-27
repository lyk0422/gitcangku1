package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.MatrixRevision;

import java.util.List;

/**
 * 兼容矩阵登记历史（只读），按版本升序。
 */
public record MatrixRevisionListResponse(List<MatrixRevision> revisions) {
}
