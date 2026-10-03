package com.example.starter.repo;

import com.example.starter.domain.Transfer;
import com.example.starter.domain.TransferStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 交接单存取：确认后持有权才转移，取消仅允许待确认状态。
 */
@Repository
public class TransferRepository {

    private static final RowMapper<Transfer> MAPPER = (rs, rowNum) -> new Transfer(
            rs.getLong("id"), rs.getString("tag_code"), rs.getString("from_station"),
            rs.getString("to_station"), TransferStatus.valueOf(rs.getString("status")),
            Times.toInstant(rs.getTimestamp("initiated_at")),
            Times.toInstant(rs.getTimestamp("confirmed_at")),
            Times.toInstant(rs.getTimestamp("cancelled_at")), rs.getString("request_id"));

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询交接单。
     */
    public Optional<Transfer> findById(long id) {
        return jdbc.query("SELECT * FROM transfer WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 按主键查询并锁定交接单行（SELECT ... FOR UPDATE），
     * 确认/取消在持有锁期间重读状态，避免基于过期状态判定。
     */
    public Optional<Transfer> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM transfer WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }
    /**
     * 查询标签当前待确认的交接单。
     */
    public Optional<Transfer> findPendingByTag(String tagCode) {
        return jdbc.query("SELECT * FROM transfer WHERE tag_code = ? AND status = 'PENDING'",
                MAPPER, tagCode).stream().findFirst();
    }

    /**
     * 发起交接，写入待确认交接单并返回主键。
     */
    public long insert(String tagCode, String fromStation, String toStation,
            Instant initiatedAt, String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO transfer (tag_code, from_station, to_station, status, initiated_at, request_id)"
                            + " VALUES (?, ?, ?, 'PENDING', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, tagCode);
            ps.setString(2, fromStation);
            ps.setString(3, toStation);
            ps.setTimestamp(4, Times.toTimestamp(initiatedAt));
            ps.setString(5, requestId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 接收站点确认：置为已确认并记录确认时刻。
     */
    public void markConfirmed(long id, Instant confirmedAt) {
        jdbc.update("UPDATE transfer SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?",
                Times.toTimestamp(confirmedAt), id);
    }

    /**
     * 发起站点取消：置为已取消并记录取消时刻。
     */
    public void markCancelled(long id, Instant cancelledAt) {
        jdbc.update("UPDATE transfer SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                Times.toTimestamp(cancelledAt), id);
    }

    /**
     * 按标签查询全部交接单，按发起时刻升序。
     */
    public List<Transfer> listByTag(String tagCode) {
        return jdbc.query("SELECT * FROM transfer WHERE tag_code = ? ORDER BY initiated_at, id",
                MAPPER, tagCode);
    }
}