package com.example.starter.observation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 未解决冲突登记器：在独立事务（REQUIRES_NEW）中提交诊断记录。
 *
 * <p>合并判定冲突后业务事务整体回滚（不写任何业务部分结果），但未解决冲突属于诊断状态，
 * 需要独立于业务回滚落库，供禁运设置校验与诊断查询使用；显式解决成功后由业务事务清除。
 */
@Component
public class OpenConflictRecorder {

    private final OpenConflictRepository openConflictRepository;

    public OpenConflictRecorder(OpenConflictRepository openConflictRepository) {
        this.openConflictRepository = openConflictRepository;
    }

    /**
     * 在独立事务中登记/刷新未解决冲突；调用方的业务事务回滚不影响该诊断记录。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(OpenConflictRecord record) {
        openConflictRepository.upsert(record);
    }
}
