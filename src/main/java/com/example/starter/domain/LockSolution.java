package com.example.starter.domain;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次成功锁定求解的结果：选中版本与去重后的精确总字节数。
 *
 * @param versions       名称 -> 精确版本（名称升序，不可变）
 * @param totalSizeBytes 选中制品大小之和：同名制品即使被多个父依赖也只计一次，根也计入；
 *                       单位字节，非负；调用方保证未发生 long 溢出
 */
public record LockSolution(Map<String, Integer> versions, long totalSizeBytes) {

    public LockSolution {
        // Map.copyOf 不保证迭代顺序；包装 TreeMap 以保留名称升序。
        versions = Collections.unmodifiableMap(new TreeMap<>(versions));
    }
}
