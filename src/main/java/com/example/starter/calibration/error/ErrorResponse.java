package com.example.starter.calibration.error;

import java.util.List;

/**
 * 统一错误响应体。
 *
 * @param status  HTTP 状态码
 * @param code    机器可识别的错误码
 * @param message 错误描述
 * @param items   批量操作各项失败原因，非批量场景为空列表
 */
public record ErrorResponse(int status, String code, String message, List<ApiException.ItemReason> items) {
}
