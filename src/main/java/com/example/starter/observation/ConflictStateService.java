package com.example.starter.observation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 未解决冲突状态服务：合并检出冲突时在独立事务落库（不随 409 业务回滚而丢失），
 * 合并/裁决/删除成功时在原事务内清除，供禁运设置的"存在未解决冲突"判定使用。
 */
@Service
public class ConflictStateService {

    private final OpenConflictRepository openConflictRepository;
    private final Clock clock;

    public ConflictStateService(OpenConflictRepository openConflictRepository, Clock clock) {
        this.openConflictRepository = openConflictRepository;
        this.clock = clock;
    }

    /**
     * 在独立事务中记录或刷新簇的未解决冲突状态：外层合并事务随后因 409 回滚，本记录仍然保留。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOpenConflict(String observationId, List<String> conflictFields,
                                   int baseVersion, int currentVersion, String requestId) {
        openConflictRepository.upsert(new OpenConflictRecord(observationId, List.copyOf(conflictFields),
                baseVersion, currentVersion, requestId, Instant.now(clock)));
    }

    /**
     * 在调用方事务内清除簇的未解决冲突状态，与业务变更原子提交。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void clearOpenConflict(String observationId) {
        openConflictRepository.delete(observationId);
    }
}