package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 拒绝事件表访问；写入发生在业务事务之外（自动提交），业务回滚不影响审计。
 */
@Repository
public class RejectionRepository {

    /** 拒绝事件：operation 操作类型，reason 拒绝原因，occurredAt 发生时刻（UTC）。 */
    public record RejectionRow(String tagCode, String animalCode, String operation,
            String reason, Instant occurredAt) {
    }

    private final JdbcClient jdbc;

    public RejectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tagCode, String animalCode, String operation, String reason, Instant occurredAt) {
        jdbc.sql("INSERT INTO rejection_event (tag_code, animal_code, operation, reason, occurred_at)"
                + " VALUES (:t, :a, :o, :r, :at)")
                .param("t", tagCode)
                .param("a", animalCode)
                .param("o", operation)
                .param("r", reason)
                .param("at", Timestamp.from(occurredAt))
                .update();
    }

    public List<RejectionRow> findByTag(String tagCode) {
        return jdbc.sql("SELECT tag_code, animal_code, operation, reason, occurred_at"
                + " FROM rejection_event WHERE tag_code = :t ORDER BY occurred_at, id")
                .param("t", tagCode)
                .query((rs, n) -> map(rs))
                .list();
    }

    public List<RejectionRow> findByAnimal(String animalCode) {
        return jdbc.sql("SELECT tag_code, animal_code, operation, reason, occurred_at"
                + " FROM rejection_event WHERE animal_code = :a ORDER BY occurred_at, id")
                .param("a", animalCode)
                .query((rs, n) -> map(rs))
                .list();
    }

    private RejectionRow map(ResultSet rs) throws SQLException {
        return new RejectionRow(
                rs.getString("tag_code"),
                rs.getString("animal_code"),
                rs.getString("operation"),
                rs.getString("reason"),
                rs.getTimestamp("occurred_at").toInstant());
    }
}