package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.IntegrityEvent;
import com.example.starter.firmware.domain.TaskFragmentReceipt;

import java.util.List;

/**
 * 任务完整性诊断视图（只读）：含登记清单、各代次接收证据与事件流水，
 * 重新拉取产生的旧代次证据与失败记录全部保留。
 */
public record TaskDiagnosticsResponse(TaskView task, int requiredCount, String packageDigest,
                                      List<AttemptView> attempts, List<EventView> events) {

    /**
     * 单个尝试代次的接收证据。
     */
    public record AttemptView(int attempt, int receivedCount, List<FragmentEvidenceView> fragments) {
    }

    /**
     * 一条分片接收证据；match 表示接收摘要与登记期望摘要是否一致。
     */
    public record FragmentEvidenceView(int seqNo, String receivedDigest, String expectedDigest,
                                       boolean match, int releaseVersion,
                                       String receivedAtUtc, String requestId) {
        public static FragmentEvidenceView of(TaskFragmentReceipt receipt) {
            return new FragmentEvidenceView(receipt.seqNo(), receipt.receivedDigest(),
                    receipt.expectedDigest(), receipt.receivedDigest().equals(receipt.expectedDigest()),
                    receipt.releaseVersion(), receipt.receivedAtUtc(), receipt.requestId());
        }
    }

    /**
     * 完整性事件流水视图，按发生顺序返回；时间为 UTC ISO-8601。
     */
    public record EventView(long eventId, int attempt, String eventType, Integer seqNo,
                            String expectedDigest, String receivedDigest,
                            String aggregateExpected, String aggregateActual,
                            String reason, String createdAtUtc) {
        public static EventView of(IntegrityEvent event) {
            return new EventView(event.id(), event.attempt(), event.eventType(), event.seqNo(),
                    event.expectedDigest(), event.receivedDigest(),
                    event.aggregateExpected(), event.aggregateActual(),
                    event.reason(), event.createdAtUtc());
        }
    }
}
