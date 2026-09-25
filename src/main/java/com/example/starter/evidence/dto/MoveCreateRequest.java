package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.TreeSet;

/**
 * 迁移申请请求。证物集合在构造时规范化（排序去重），集合换序视为同参；
 * moveKey 指纹含操作者、规范化证物集合、源目标库位与版本。
 *
 * @param moveKey         迁移业务键（幂等键）
 * @param evidenceKeys    证物键集合，非空；构造时排序去重
 * @param sourceLocation  源库位编码
 * @param targetLocation  目标库位编码
 * @param expectedVersion 申请时源库位库存版本
 */
public record MoveCreateRequest(
        @NotBlank @Size(max = 64) String moveKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotBlank @Size(max = 64) String sourceLocation,
        @NotBlank @Size(max = 64) String targetLocation,
        @NotNull @PositiveOrZero Integer expectedVersion) {

    /**
     * 规范化证物集合：排序去重并固定为不可变列表，保证换序与重复元素视为同参。
     */
    public MoveCreateRequest {
        evidenceKeys = List.copyOf(new TreeSet<>(evidenceKeys));
    }
}
