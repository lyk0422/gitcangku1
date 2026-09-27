package com.example.starter.evidence;

import com.example.starter.evidence.dto.MoveApplyRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 迁移申请编排（非事务）：规范化证物集合、计算申请指纹、
 * 处理并发下 move_key 唯一约束冲突并重放先提交事务的完整迁移结果。
 * 指纹含操作者、规范化证物集合、源目标库位和版本；同键成功重放完整迁移结果，失败不占键。
 */
@Component
public class MoveOrchestrator {

    private final MoveService moveService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public MoveOrchestrator(MoveService moveService, IdempotencyAdvisor idempotencyAdvisor) {
        this.moveService = moveService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 规范化申请参数并执行迁移申请；并发重复申请时回滚后重放先提交事务的结果。
     */
    public StoredResponse apply(String actorId, MoveApplyRequest request) {
        List<String> keys = MoveService.normalizeKeys(request.evidenceKeys());
        MoveApplyRequest canonical = new MoveApplyRequest(request.moveKey(), keys,
                request.sourceLocation(), request.targetLocation(), request.expectedVersion());
        String hash = idempotencyAdvisor.hash(MoveService.OP_MOVE_APPLY, actorId,
                request.moveKey(), canonical);
        try {
            return moveService.apply(actorId, canonical, hash);
        } catch (DuplicateKeyException e) {
            return moveService.replayAfterDuplicate(request.moveKey(), hash);
        }
    }
}
