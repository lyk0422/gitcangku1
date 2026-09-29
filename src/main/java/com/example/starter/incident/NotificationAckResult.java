package com.example.starter.incident;

import com.example.starter.incident.dto.Responses.NotificationView;

/**
 * 回执录入结果：view 为录入后的通报视图；firstAck 为 true 表示本次是该机构首份有效回执
 * （计入完成数），false 表示该机构已有回执，本次重复报文未改写首份结果。
 */
public record NotificationAckResult(NotificationView view, boolean firstAck) {
}
