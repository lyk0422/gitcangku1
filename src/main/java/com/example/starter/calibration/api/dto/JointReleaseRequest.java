package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 联合批次放行请求。每批 2～20 条测量键，可混合不同仪器，整批原子生效。
 *
 * @param jointBatchKey 联合批次键，全局唯一（幂等键）；同键同参重放首次响应快照，异参 409，失败不占键
 * @param keys          测量键列表；集合换序视为同参
 */
public record JointReleaseRequest(String jointBatchKey, List<String> keys) {
}
