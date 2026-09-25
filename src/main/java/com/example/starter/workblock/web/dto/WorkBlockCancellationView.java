package com.example.starter.workblock.web.dto;

/**
 * 施工单取消不可变记录视图。
 */
public record WorkBlockCancellationView(String workKey, int version, String operator,
                                        long cancelledAt) {
}
