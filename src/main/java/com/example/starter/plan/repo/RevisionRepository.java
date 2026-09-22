package com.example.starter.plan.repo;

import com.example.starter.plan.model.PlanRevision;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 改签前后继关联持久化。关联一经追加不可变、不删除；
 * 前驱/后继各有唯一约束，保证一个计划最多一个直接前驱和一个直接后继。
 */
@Repository
public class RevisionRepository {

    private static final RowMapper<PlanRevision> REVISION_MAPPER = (rs, n) -> new PlanRevision(
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public RevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条改签关联；前驱或后继唯一约束冲突时抛出 DuplicateKeyException。
     */
    public void insert(long predecessorPlanId, long successorPlanId, long nowMillis) {
        jdbc.update("INSERT INTO rail_plan_revision"
                        + " (predecessor_plan_id, successor_plan_id, created_at) VALUES (?, ?, ?)",
                predecessorPlanId, successorPlanId, nowMillis);
    }

    /**
     * 按计划 id 查询其作为前驱的关联（即它的直接后继）。
     */
    public Optional<PlanRevision> findByPredecessor(long predecessorPlanId) {
        return jdbc.query("SELECT predecessor_plan_id, successor_plan_id, created_at"
                        + " FROM rail_plan_revision WHERE predecessor_plan_id = ?",
                REVISION_MAPPER, predecessorPlanId).stream().findFirst();
    }

    /**
     * 按计划 id 查询其作为后继的关联（即它的直接前驱）。
     */
    public Optional<PlanRevision> findBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT predecessor_plan_id, successor_plan_id, created_at"
                        + " FROM rail_plan_revision WHERE successor_plan_id = ?",
                REVISION_MAPPER, successorPlanId).stream().findFirst();
    }

    /**
     * 查询从链起点（rootPlanId 应是没有前驱的最早计划）开始的全部关联，
     * 按改签提交顺序返回；链式关系在内存中沿直接后继逐跳查询，避免依赖数据库递归语法。
     */
    public List<PlanRevision> findChainFromRoot(long rootPlanId) {
        List<PlanRevision> chain = new java.util.ArrayList<>();
        long current = rootPlanId;
        while (true) {
            Optional<PlanRevision> next = findByPredecessor(current);
            if (next.isEmpty()) {
                break;
            }
            chain.add(next.get());
            current = next.get().successorPlanId();
        }
        return chain;
    }

    /**
     * 沿后继链反查链起点：不断寻找当前计划的直接前驱，直到没有前驱为止。
     */
    public long findRootPlanId(long anyPlanId) {
        long current = anyPlanId;
        while (true) {
            Optional<PlanRevision> prev = findBySuccessor(current);
            if (prev.isEmpty()) {
                return current;
            }
            current = prev.get().predecessorPlanId();
        }
    }
}
