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
 * 接触批次与事件序列表访问。事件序号通过 contact_event_seq 单行行锁分配；
 * 批次登记取号与污染传播扫描共用该串行边界，批次与成员记录只追加。
 */
@Repository
public class ContactBatchRepository {

    private static final ContactBatchRowMapper ROW_MAPPER = new ContactBatchRowMapper();

    private final JdbcTemplate jdbc;

    public ContactBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在序列行锁内取出并递增下一个事件序号（调用方须在事务内；序号随事务提交生效）。
     */
    public long nextEventSeq() {
        long next = lockSequenceRow();
        jdbc.update("UPDATE contact_event_seq SET next_seq = ? WHERE id = 1", next + 1);
        return next;
    }

    /**
     * 仅获取序列行锁以串行化污染传播，不消耗序号。
     */
    public void lockSequence() {
        lockSequenceRow();
    }

    private long lockSequenceRow() {
        List<Long> rows = jdbc.query("SELECT next_seq FROM contact_event_seq WHERE id = 1 FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1));
        if (rows.isEmpty()) {
            throw new IllegalStateException("接触事件序列未初始化");
        }
        return rows.get(0);
    }

    /**
     * 追加接触批次。
     */
    public void insertBatch(String batchKey, long eventSeq, String custodianId,
                            int memberCount, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO contact_batch
                            (batch_key, event_seq, custodian_id, member_count, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                batchKey, eventSeq, custodianId, memberCount, createdAt);
    }

    /**
     * 追加批次成员。
     */
    public void insertMember(String batchKey, String evidenceKey, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO contact_batch_member
                            (batch_key, evidence_key, created_at)
                        VALUES (?, ?, ?)
                        """,
                batchKey, evidenceKey, createdAt);
    }

    /**
     * 按业务键查询批次（不加锁）。
     */
    public Optional<ContactBatch> findBatchByKey(String batchKey) {
        List<ContactBatch> rows = jdbc.query(
                "SELECT * FROM contact_batch WHERE batch_key = ?", ROW_MAPPER, batchKey);
        return rows.stream().findFirst();
    }

    /**
     * 按登记顺序查询批次成员证物键。
     */
    public List<String> findMembers(String batchKey) {
        return jdbc.query(
                "SELECT evidence_key FROM contact_batch_member WHERE batch_key = ? ORDER BY id",
                (rs, rowNum) -> rs.getString(1), batchKey);
    }

    /**
     * 查询事件序号不小于 startSeq 的全部批次，按序号递增排序（污染传播扫描用）。
     */
    public List<ContactBatch> findBatchesFromSeq(long startSeq) {
        return jdbc.query(
                "SELECT * FROM contact_batch WHERE event_seq >= ? ORDER BY event_seq",
                ROW_MAPPER, startSeq);
    }

    /**
     * 判断事件序号是否已存在（污染起点必须是已有序号）。
     */
    public boolean existsBySeq(long eventSeq) {
        List<Long> rows = jdbc.query(
                "SELECT event_seq FROM contact_batch WHERE event_seq = ?",
                (rs, rowNum) -> rs.getLong(1), eventSeq);
        return !rows.isEmpty();
    }

    private static final class ContactBatchRowMapper implements RowMapper<ContactBatch> {
        @Override
        public ContactBatch mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContactBatch(
                    rs.getLong("id"),
                    rs.getString("batch_key"),
                    rs.getLong("event_seq"),
                    rs.getString("custodian_id"),
                    rs.getInt("member_count"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
