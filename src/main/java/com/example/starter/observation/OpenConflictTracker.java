package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 合并冲突落痕器：在独立事务中记录未解决冲突状态。
 * 合并冲突发生时主事务整体回滚（失败不占键），冲突状态作为业务事实须独立提交，
 * 供后续禁运设置校验“簇存在未解决冲突”使用。
 */
@Service
public class OpenConflictTracker {

    private final OpenConflictRepository openConflictRepository;
    private final Clock clock;

    public OpenConflictTracker(OpenConflictRepository openConflictRepository, Clock clock) {
        this.openConflictRepository = openConflictRepository;
        this.clock = clock;
    }

    /**
     * 在独立事务中落痕一次合并冲突；调用方（已回滚的合并事务）抛出 409 前由外层包装调用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOpenConflict(String observationId, List<String> conflictFields, int currentVersion) {
        openConflictRepository.upsert(observationId, List.copyOf(conflictFields), currentVersion,
                Instant.now(clock));
    }
}
