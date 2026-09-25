package com.example.starter.evidence;

/**
 * 容器巡检封签结果。PASS 通过；FAIL 失败（容器转 INSPECTION_FAILED，说明非空）。
 */
public enum SealResult {
    PASS,
    FAIL
}
