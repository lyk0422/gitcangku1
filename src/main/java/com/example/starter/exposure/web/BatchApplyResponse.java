package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量预占结果：全部成功时按入参顺序返回各访客预占单。
 *
 * @param reservations 预占单视图列表，顺序与请求条目一致
 */
public record BatchApplyResponse(
        List<ReservationResponse> reservations
) {
}
