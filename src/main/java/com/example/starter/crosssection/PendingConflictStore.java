package com.example.starter.crosssection;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 未决冲突标记的独立事务存储：整组合并冲突时业务事务必须回滚（失败不占 requestId），
 * 但未决标记需要保留供裁决与成果 422 核验，因此以 REQUIRES_RES 新事务提交，
 * 不受外层业务事务回滚影响。删除标记同样独立提交，避免被外层回滚意外恢复。
 */
@Component
public class PendingConflictStore {

    private final CrossSectionRepository repository;

    public PendingConflictStore(CrossSectionRepository repository) {
        this.repository = repository;
    }

    /**
     * 在独立事务中写入或覆盖未决冲突标记。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(PendingConflict conflict) {
        repository.upsertPendingConflict(conflict);
    }

    /**
     * 在独立事务中清除未决冲突标记。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void remove(String observationId) {
        repository.deletePendingConflict(observationId);
    }
}
