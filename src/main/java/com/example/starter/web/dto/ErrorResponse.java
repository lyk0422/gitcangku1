package com.example.starter.web.dto;

import java.util.List;

/**
 * 错误响应。
 *
 * @param message 拒绝或错误原因
 * @param cellCodes 涉及的电芯编号，无涉及时为空表
 */
public record ErrorResponse(String message, List<String> cellCodes) {
}