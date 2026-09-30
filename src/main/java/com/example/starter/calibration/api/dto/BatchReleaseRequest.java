package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批次放行请求。measurementKeys 省略时放行当前修订下全部参与统计的读数；
 * 显式给出时必须是当前修订的活动读数集合（排除已 EXCLUDED）。
 *
 * @param measurementKeys 待放行测量键；null/空表示全部活动读数
 */
public record BatchReleaseRequest(List<String> measurementKeys) {
}
