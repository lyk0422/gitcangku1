package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量修改不确定度分量请求：整体替换当前分量集合，先校验完整预算再在一个事务中提交。
 *
 * @param requestId       幂等请求 ID（必填；同参重放返回首次快照，异参 409，失败不占键）
 * @param expectedVersion 期望的测量版本号；与当前版本不一致返回 410
 * @param components      完整分量集合（可为空数组表示清空；null 视为参数非法）
 */
public record UpdateComponentsRequest(String requestId, Integer expectedVersion,
                                      List<ComponentInput> components) {
}
