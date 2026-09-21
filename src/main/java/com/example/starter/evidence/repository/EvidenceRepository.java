package com.example.starter.evidence.repository;

import com.example.starter.evidence.domain.Evidence;
import com.example.starter.evidence.domain.EvidenceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 证物主表访问。状态与保管人的并发变更依赖 {@link #findByKeyForUpdate} 行锁串行化。
 */
@Repository
public class EvidenceRepository {

    private static final RowMapper<Evidence> MAPPER = (rs, rowNum) -> new Evidence(
            rs.getLong("id"),
            rs.getString("evidence_key"),
            rs.getString("case_key"),
            rs.getString("category"),
            rs.getString("seal_no"),
            rs.getString("custodian_id"),
            EvidenceStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新证物，初始状态 SEALED，返回生成主键。
     */
    public long insert(String evidenceKey, String caseKey, String category, String sealNo,
                       String custodianId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO evidence (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, evidenceKey);
            ps.setString(2, caseKey);
            ps.setString(3, category);
            ps.setString(4, sealNo);
            ps.setString(5, custodianId);
            ps.setString(6, EvidenceStatus.SEALED.name());
            ps.setTimestamp(7, Timestamp.valueOf(now));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public Optional<Evidence> findByKey(String evidenceKey) {
        List<Evidence> rows = jdbc.query(
                "SELECT * FROM evidence WHERE evidence_key = ?", MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键加行锁读取（SELECT ... FOR UPDATE），用于交接/核验等变更的事务串行化。
     */
    public Optional<Evidence> findByKeyForUpdate(String evidenceKey) {
        List<Evidence> rows = jdbc.query(
                "SELECT * FROM evidence WHERE evidence_key = ? FOR UPDATE", MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 更新状态（保管人不变）。
     */
    public void updateStatus(long id, EvidenceStatus status, LocalDateTime now) {
        jdbc.update("UPDATE evidence SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.valueOf(now), id);
    }

    /**
     * 交接接受时原子切换保管人并回到 SEALED。
     */
    public void updateCustodianAndStatus(long id, String custodianId, EvidenceStatus status, LocalDateTime now) {
        jdbc.update("UPDATE evidence SET custodian_id = ?, status = ?, updated_at = ? WHERE id = ?",
                custodianId, status.name(), Timestamp.valueOf(now), id);
    }

    /**
     * 查询某保管人当前可交接（本人保管且状态 SEALED）的证物。
     */
    public List<Evidence> findTransferable(String custodianId) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE custodian_id = ? AND status = ? ORDER BY id",
                MAPPER, custodianId, EvidenceStatus.SEALED.name());
    }
}
