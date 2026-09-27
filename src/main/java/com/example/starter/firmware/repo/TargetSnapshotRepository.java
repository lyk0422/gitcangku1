package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.LabelExpression;
import com.example.starter.firmware.domain.TargetSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 发布单目标快照数据访问。快照只在创建发布单时写入一次，之后只读。
 */
@Repository
public class TargetSnapshotRepository {

    private record ExpressionJson(List<String> mustInclude, List<String> mustExclude, List<String> includeAny) {
    }

    private static final TypeReference<ExpressionJson> EXPRESSION_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TargetSnapshotRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(TargetSnapshot snapshot) {
        jdbc.update("INSERT INTO release_target_snapshot"
                        + " (release_id, expression_json, label_dict_version, target_count, resolved_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?)",
                snapshot.releaseId(), writeExpression(snapshot.expression()),
                snapshot.labelDictVersion(), snapshot.targetDevices().size(), snapshot.resolvedAtUtc());
    }

    public void insertMember(long releaseId, String deviceId) {
        jdbc.update("INSERT INTO release_target_device (release_id, device_id) VALUES (?, ?)", releaseId, deviceId);
    }

    public Optional<TargetSnapshot> findByRelease(long releaseId) {
        return jdbc.query("SELECT release_id, expression_json, label_dict_version, target_count, resolved_at_utc"
                        + " FROM release_target_snapshot WHERE release_id = ?",
                (rs, rowNum) -> {
                    LabelExpression expression = readExpression(rs.getString("expression_json"));
                    List<String> devices = jdbc.queryForList(
                            "SELECT device_id FROM release_target_device WHERE release_id = ? ORDER BY device_id",
                            String.class, releaseId);
                    return new TargetSnapshot(rs.getLong("release_id"), expression,
                            rs.getLong("label_dict_version"), devices, rs.getString("resolved_at_utc"));
                }, releaseId).stream().findFirst();
    }

    /**
     * 快照成员判定（只读，不锁定）：后续拉取仅对快照成员下发。
     */
    public boolean isMember(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_target_device WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count != null && count > 0;
    }

    public long countMembers(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_target_device WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }

    private String writeExpression(LabelExpression expression) {
        try {
            return objectMapper.writeValueAsString(new ExpressionJson(expression.mustInclude(),
                    expression.mustExclude(), expression.includeAny()));
        } catch (Exception e) {
            throw new IllegalStateException("标签表达式序列化失败", e);
        }
    }

    private LabelExpression readExpression(String json) {
        try {
            ExpressionJson value = objectMapper.readValue(json, EXPRESSION_TYPE);
            return new LabelExpression(value.mustInclude(), value.mustExclude(), value.includeAny());
        } catch (Exception e) {
            throw new IllegalStateException("标签表达式反序列化失败: " + json, e);
        }
    }
}
