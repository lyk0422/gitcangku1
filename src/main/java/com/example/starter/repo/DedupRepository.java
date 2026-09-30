package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 写操作幂等去重数据访问，供各业务服务共用。
 * 同键同参重放首次成功结果，异参/异种操作冲突；失败不占键。
 */
@Repository
public class DedupRepository {

    private final JdbcTemplate jdbc;

    public DedupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询幂等去重记录，不存在返回 null。 */
    public DedupPo findDedup(String requestId) {
        return jdbc.query(
                "SELECT request_id, request_kind, request_hash, response_json, created_at "
                        + "FROM request_dedup WHERE request_id = ?",
                (rs, n) -> new DedupPo(rs.getString("request_id"), rs.getString("request_kind"),
                        rs.getString("request_hash"), rs.getString("response_json"),
                        rs.getLong("created_at")),
                requestId).stream().findFirst().orElse(null);
    }

    /** 插入幂等去重记录（与业务变更在同一事务提交）。 */
    public void insertDedup(DedupPo po) {
        jdbc.update("INSERT INTO request_dedup "
                        + "(request_id, request_kind, request_hash, response_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                po.requestId(), po.requestKind(), po.requestHash(), po.responseJson(), po.createdAt());
    }
}
