package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseRequestSnapshot;

/**
 * 放行请求快照持久化。requestId 主键冲突用于并发重放去重；快照只增不改。
 */
@Repository
public class ReleaseRequestRepository {

    private static final RowMapper<ReleaseRequestSnapshot> MAPPER = (rs, rowNum) ->
            new ReleaseRequestSnapshot(
                    rs.getString("request_id"),
                    rs.getString("batch_id"),
                    rs.getString("released_by"),
                    rs.getString("fingerprint"),
                    JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReleaseRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入首次放行快照；requestId 已存在时抛出 DuplicateKeyException。
     * 须在放行事务内调用：事务回滚时快照一并消失，不污染重放。
     */
    public void insert(ReleaseRequestSnapshot snapshot) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_request (request_id, batch_id, released_by, fingerprint, released_at) "
                            + "VALUES (?, ?, ?, ?, ?)");
            ps.setString(1, snapshot.id());
            ps.setString(2, snapshot.batchId());
            ps.setString(3, snapshot.releasedBy());
            ps.setString(4, snapshot.fingerprint());
            ps.setObject(5, JdbcTimes.toDb(snapshot.releasedAt()));
            return ps;
        });
    }

    /**
     * 按 requestId 查询并对快照行加锁（须在事务内调用），串行化同一 requestId 的并发重放。
     */
    public Optional<ReleaseRequestSnapshot> findByIdForUpdate(String requestId) {
        return jdbc.query("SELECT * FROM release_request WHERE request_id = ? FOR UPDATE",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 按 requestId 查询（不加锁）。
     */
    public Optional<ReleaseRequestSnapshot> findById(String requestId) {
        return jdbc.query("SELECT * FROM release_request WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }
}
