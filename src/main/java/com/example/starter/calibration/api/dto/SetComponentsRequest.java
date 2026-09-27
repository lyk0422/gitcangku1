package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量修改不确定度分量请求：整体替换该测量的分量集合，先校验完整预算再原子提交。
 *
 * @param requestId          幂等请求 ID（必填；同参重放返回首次快照，异参 409，失败不占键）
 * @param measurementVersion 期望的测量版本（必填；与当前版本不一致返回 410）
 * @param components         完整分量集合（可为空数组，表示清空预算）
 */
public record SetComponentsRequest(String requestId, Integer measurementVersion,
                                   List<ComponentInput> components) {
}
