package com.example.starter.evidence.dto;

import com.example.starter.evidence.InspectionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 容器巡检视图：巡检人、封条结果、见证版本及确认时箱内隔离清单。
 *
 * @param requestId        巡检请求键（幂等键）
 * @param containerKey     关联容器业务键
 * @param containerVersion 见证的容器版本
 * @param sealIntact       封条结果：true 完好 / false 异常
 * @param status           巡检状态
 * @param firstInspector   首位巡检保管人
 * @param secondInspector  第二位巡检保管人；null 表示待第二人确认
 * @param confirmedAt      双人确认完成时刻（Asia/Shanghai）；null 表示未完成
 * @param items            确认时箱内隔离清单（封条完好或待确认时为空）
 * @param createdAt        首次提交时间（Asia/Shanghai）
 */
public record ContainerInspectionView(
        String requestId,
        String containerKey,
        long containerVersion,
        boolean sealIntact,
        InspectionStatus status,
        String firstInspector,
        String secondInspector,
        LocalDateTime confirmedAt,
        List<ContainerIsolationView> items,
        LocalDateTime createdAt) {
}
