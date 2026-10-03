package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.TransferRow;
import com.example.starter.domain.TransferStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 交接单表访问；确认/取消前须先持有对应标签行锁（在 TagRepository 中完成）。
 */
@Repository
public class TransferRepository {

    private static final String COLUMNS =
            "id, tag_code, from_station_code, to_station_code, status, initiated_at, confirmed_at, request_id";

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TransferRow insert(String tagCode, String fromStation, String toStation,
            Instant initiatedAt, String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO tag_transfer"
                + " (tag_code, from_station_code, to_station_code, status, initiated_at, request_id)"
                + " VALUES (:t, :f, :to, 'PENDING', :at, :r)")
                .param("t", tagCode)
                .param("f", fromStation)
                .param("to", toStation)
                .param("at", Timestamp.from(initiatedAt))
                .param("r", requestId)
                .update(keys);
        Number key = keys.getKey();
        return new TransferRow(key == null ? null : key.longValue(), tagCode, fromStation, toStation,
                TransferStatus.PENDING, initiatedAt, null, requestId);
    }

    public Optional<TransferRow> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tag_transfer WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<TransferRow> findPendingByTag(String tagCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tag_transfer"
                + " WHERE tag_code = :t AND status = 'PENDING'")
                .param("t", tagCode)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public void updateStatus(long id, TransferStatus status, Instant confirmedAt) {
        jdbc.sql("UPDATE tag_transfer SET status = :s, confirmed_at = :c WHERE id = :id")
                .param("s", status.name())
                .param("c", confirmedAt == null ? null : Timestamp.from(confirmedAt))
                .param("id", id)
                .update();
    }

    public List<TransferRow> findByTag(String tagCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tag_transfer WHERE tag_code = :t ORDER BY initiated_at, id")
                .param("t", tagCode)
                .query((rs, n) -> map(rs))
                .list();
    }

    private TransferRow map(ResultSet rs) throws SQLException {
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        return new TransferRow(
                rs.getLong("id"),
                rs.getString("tag_code"),
                rs.getString("from_station_code"),
                rs.getString("to_station_code"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("initiated_at").toInstant(),
                confirmed == null ? null : confirmed.toInstant(),
                rs.getString("request_id"));
    }
}