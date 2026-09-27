package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 封签版本表访问。版本只追加、不可变；检验开始时以条件更新锁定（封存）当前版本，
 * 检验完成后追加下一版本。不提供任何删除或改写旧版本的语句。
 */
@Repository
public class SealVersionRepository {

    private static final SealVersionRowMapper ROW_MAPPER = new SealVersionRowMapper();

    private final JdbcTemplate jdbc;

    public SealVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个封签版本。
     */
    public void insert(String evidenceKey, int version, String sealNo, SealVersionKind kind,
                       String createdBy, SealVersionEvent eventType, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO seal_version
                            (evidence_key, version, seal_no, kind, created_by, event_type, created_at, superseded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                evidenceKey, version, sealNo, kind.name(), createdBy, eventType.name(), createdAt);
    }

    /**
     * 按版本顺序查询证物全部封签版本（历史不可变查询）。
     */
    public List<SealVersion> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM seal_version WHERE evidence_key = ? ORDER BY version",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 查询证物当前有效（未被检验开始锁定）的封签版本（不加锁）。
     */
    public Optional<SealVersion> findCurrent(String evidenceKey) {
        List<SealVersion> rows = jdbc.query(
                "SELECT * FROM seal_version WHERE evidence_key = ? AND superseded_at IS NULL ORDER BY version DESC",
                ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询并锁定证物当前有效封签版本行（SELECT ... FOR UPDATE），用于检验开始。
     */
    public Optional<SealVersion> findCurrentForUpdate(String evidenceKey) {
        List<SealVersion> rows = jdbc.query("""
                        SELECT * FROM seal_version
                        WHERE evidence_key = ? AND superseded_at IS NULL
                        ORDER BY version DESC
                        FOR UPDATE
                        """,
                ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 锁定（封存）当前版本：仅当尚未被锁定时写入锁定时刻。
     *
     * @return 是否成功锁定（false 表示已被并发检验先行锁定）
     */
    public boolean supersede(long id, LocalDateTime supersededAt) {
        int updated = jdbc.update("""
                        UPDATE seal_version
                        SET superseded_at = ?
                        WHERE id = ? AND superseded_at IS NULL
                        """,
                supersededAt, id);
        return updated == 1;
    }

    private static final class SealVersionRowMapper implements RowMapper<SealVersion> {
        @Override
        public SealVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SealVersion(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getInt("version"),
                    rs.getString("seal_no"),
                    SealVersionKind.valueOf(rs.getString("kind")),
                    rs.getString("created_by"),
                    SealVersionEvent.valueOf(rs.getString("event_type")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("superseded_at", LocalDateTime.class));
        }
    }
}
