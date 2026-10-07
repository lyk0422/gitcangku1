package com.example.starter.plan.repo;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 实际进出写操作幂等记录持久化。requestId 全局唯一；仅缓存成功结果，失败不占键。
 */
@Repository
public class ActualIdempotencyRepository {

    /**
     * 幂等记录，结构化保存操作、资源、操作者与期望版本用于同参判定。
     *
     * @param opType          操作类型：ENTER / EXIT
     * @param resource        操作资源：scheduleKey#占用序号
     * @param operator        操作者（文本原样比较）
     * @param expectedVersion 期望计划版本（十进制按值比较）
     * @param responseJson    首次成功响应快照
     */
    public record Record(String opType, String resource, String operator, int expectedVersion,
                         String responseJson) {
    }

    private final JdbcTemplate jdbc;

    public ActualIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按全局请求 id 查询记录。
     */
    public Optional<Record> find(String requestId) {
        return jdbc.query("SELECT op_type, resource, operator_name, expected_version, response_json"
                        + " FROM actual_write_record WHERE request_id = ?",
                (rs, n) -> new Record(rs.getString("op_type"), rs.getString("resource"),
                        rs.getString("operator_name"), rs.getInt("expected_version"),
                        rs.getString("response_json")),
                requestId).stream().findFirst();
    }

    /**
     * 写入幂等记录；requestId 冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insert(String requestId, String opType, String resource, String operator,
                       int expectedVersion, String paramsJson, String responseJson,
                       long nowMillis) {
        jdbc.update("INSERT INTO actual_write_record"
                        + " (request_id, op_type, resource, operator_name, expected_version,"
                        + " params_json, response_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, opType, resource, operator, expectedVersion, paramsJson, responseJson,
                nowMillis);
    }
}
