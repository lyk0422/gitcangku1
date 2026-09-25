package com.example.starter.consent;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.BatchQueryRepository.BatchRow;
import com.example.starter.consent.BatchQueryRepository.BlockRow;

/**
 * 批次阻断审计：批次门禁失败在独立事务中提交，保证外层业务因 403 回滚后，
 * “批次查询阻断明细”仍可查询，同时失败请求不写入幂等表（不占 requestId）。
 */
@Service
public class BatchBlockAuditService {

    private final BatchQueryRepository batchQueryRepository;

    public BatchBlockAuditService(BatchQueryRepository batchQueryRepository) {
        this.batchQueryRepository = batchQueryRepository;
    }

    /**
     * 在独立事务中登记 BLOCKED 批次及其阻断明细。
     *
     * @param batchId     新生成的批次标识
     * @param recipientId 接收方标识
     * @param purpose     查询用途
     * @param recordKey   记录键
     * @param createdAt   批次时刻（UTC）
     * @param blocks      稳定排序后的阻断明细
     * @return 已登记的批次标识
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordBlocked(String batchId, String recipientId, Purpose purpose, String recordKey,
                                Instant createdAt, List<BlockRow> blocks) {
        batchQueryRepository.insertBatch(
                new BatchRow(batchId, recipientId, purpose, recordKey, "BLOCKED", createdAt), null);
        int lineNo = 0;
        for (BlockRow block : blocks) {
            batchQueryRepository.insertBlock(block, lineNo++);
        }
        return batchId;
    }
}
