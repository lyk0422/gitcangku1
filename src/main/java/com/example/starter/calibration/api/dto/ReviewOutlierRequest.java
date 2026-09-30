package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 离群复核请求。ACCEPT 只保留读数并记录理由；REJECT 在同议成裁的同一事务内排除读数并重算。
 * 引用版本可由调用方显式断言（缺省由服务端按当前版本盖章）：与当前版本不一致时按 STALE 处理（410）。
 *
 * @param decision            复核结论：ACCEPT / REJECT
 * @param reason              复核理由（必填）
 * @param measurementRevision 调用方引用的测量修订版本号；null 表示使用当前版本
 * @param certificateId       调用方引用的证书版本 ID；null 表示使用读数绑定的证书
 */
public record ReviewOutlierRequest(
        String decision,
        String reason,
        Integer measurementRevision,
        Long certificateId) {
}
