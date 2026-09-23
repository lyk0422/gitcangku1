package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 放行后复核请求。驳回集合可包含 1～全部测量，逐项给出版本与原因。批次 ID 由路径提供。
 *
 * @param reviewKey  复核幂等键，全局唯一；同参换序重放返回原结果，异参 409
 * @param rejections 驳回项列表（至少 1 项，至多批次全部位置，不得重复位置）
 */
public record ReviewRequest(String reviewKey, List<RejectionRequest> rejections) {
}
