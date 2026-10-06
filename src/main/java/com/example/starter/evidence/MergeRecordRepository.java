package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合并事件表访问。记录只追加；冻结快照在合并事务内写入后不可变，
 * 后续主号交接不回写副号历史。
 */
@Repository
public class MergeRecordRepository {

    private static final MergeRowMapper ROW_MAPPER = new MergeRowMapper();

    private final JdbcTemplate jdbc;

    public MergeRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条合并事件及两证物冻结快照。
     */
    public void insert(String primaryKey, String aliasKey, String actorId,
                       long primaryVersion, long aliasVersion,
                       String primarySnapshot, String aliasSnapshot, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO merge_record
                            (primary_key, alias_key, actor_id, primary_version, alias_version,
                             primary_snapshot, alias_snapshot, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                primaryKey, aliasKey, actorId, primaryVersion, aliasVersion,
                primarySnapshot, aliasSnapshot, now);
    }

    /**
     * 按发生顺序查询涉及该证物的合并事件（作为主号或被合并号）。
     */
    public List<MergeRecord> findInvolving(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM merge_record WHERE primary_key = ? OR alias_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey, evidenceKey);
    }

    /**
     * 合并事件记录。
     *
     * @param id              主键
     * @param primaryKey      合并后保留的主号证物业务键
     * @param aliasKey        被合并转为别名的证物业务键
     * @param actorId         执行合并的操作人
     * @param primaryVersion  合并完成时主号版本
     * @param aliasVersion    合并完成时别名版本
     * @param primarySnapshot 合并时主号完整保管链冻结快照 JSON
     * @param aliasSnapshot   合并时别名完整保管链冻结快照 JSON
     * @param createdAt       合并时间（Asia/Shanghai）
     */
    public record MergeRecord(
            long id,
            String primaryKey,
            String aliasKey,
            String actorId,
            long primaryVersion,
            long aliasVersion,
            String primarySnapshot,
            String aliasSnapshot,
            LocalDateTime createdAt) {
    }

    private static final class MergeRowMapper implements RowMapper<MergeRecord> {
        @Override
        public MergeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MergeRecord(
                    rs.getLong("id"),
                    rs.getString("primary_key"),
                    rs.getString("alias_key"),
                    rs.getString("actor_id"),
                    rs.getLong("primary_version"),
                    rs.getLong("alias_version"),
                    rs.getString("primary_snapshot"),
                    rs.getString("alias_snapshot"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
