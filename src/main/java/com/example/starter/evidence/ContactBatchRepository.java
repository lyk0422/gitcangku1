package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 接触批次表访问。批次只追加，event_seq 由自增主键生成，全局单调递增，
 * 作为污染传播的扫描边界；成员与批次同事务写入，历史冻结不可修改。
 */
@Repository
public class ContactBatchRepository {

    private static final BatchRowMapper BATCH_MAPPER = new BatchRowMapper();

    private final JdbcTemplate jdbc;

    public ContactBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 锁定接触域串行单例行（SELECT ... FOR UPDATE）。
     * 接触批次登记与污染登记在事务开始时先取该锁，保证两类写操作串行提交。
     */
    public void lockDomain() {
        jdbc.queryForObject("SELECT id FROM contact_domain_lock WHERE id = 1 FOR UPDATE",
                Long.class);
    }

    /**
     * 追加一个接触批次，返回生成的事件序号（单调递增）。
     */
    public long insertBatch(String custodianId, LocalDateTime createdAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO contact_batch (custodian_id, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, custodianId);
            ps.setTimestamp(2, Timestamp.valueOf(createdAtUtc));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("接触批次事件序号生成失败");
        }
        return key.longValue();
    }

    /**
     * 追加批次成员；同一批次内证物不重复（唯一约束兜底）。
     */
    public void insertMember(long eventSeq, String evidenceKey) {
        jdbc.update("INSERT INTO contact_batch_member (event_seq, evidence_key) VALUES (?, ?)",
                eventSeq, evidenceKey);
    }

    /**
     * 事件序号是否已存在（污染起点必须引用已有批次）。
     */
    public boolean existsBatch(long eventSeq) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_batch WHERE event_seq = ?", Integer.class, eventSeq);
        return count != null && count > 0;
    }

    /**
     * 按事件序号递增查询起点及之后的全部批次（含成员），用于污染传播扫描。
     */
    public List<ContactBatch> findBatchesFrom(long fromEventSeq) {
        return jdbc.query(
                "SELECT * FROM contact_batch WHERE event_seq >= ? ORDER BY event_seq",
                BATCH_MAPPER, fromEventSeq);
    }

    /**
     * 查询指定批次的全部成员证物键（按证物键排序，保证锁定顺序确定）。
     */
    public List<String> findMemberKeys(long eventSeq) {
        return jdbc.queryForList(
                "SELECT evidence_key FROM contact_batch_member WHERE event_seq = ? ORDER BY evidence_key",
                String.class, eventSeq);
    }

    /**
     * 接触批次记录。
     *
     * @param eventSeq    接触事件序号，全局单调递增
     * @param custodianId 登记时的当前保管人
     * @param createdAt   批次登记时刻（UTC）
     */
    public record ContactBatch(long eventSeq, String custodianId, LocalDateTime createdAt) {
    }

    private static final class BatchRowMapper implements RowMapper<ContactBatch> {
        @Override
        public ContactBatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContactBatch(
                    rs.getLong("event_seq"),
                    rs.getString("custodian_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
