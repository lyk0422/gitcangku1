package com.example.starter.archival.repo;

import com.example.starter.archival.domain.BatchMember;
import com.example.starter.archival.domain.MemberStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 批次-档案页在组关系仓储。
 */
@Repository
public class MemberRepository {

    private static final RowMapper<BatchMember> MAPPER = (rs, rowNum) -> {
        Timestamp releasedAt = rs.getTimestamp("released_at");
        return new BatchMember(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getLong("page_id"),
                MemberStatus.valueOf(rs.getString("member_status")),
                rs.getTimestamp("joined_at").toLocalDateTime(),
                releasedAt == null ? null : releasedAt.toLocalDateTime(),
                rs.getString("request_id"));
    };

    private final JdbcTemplate jdbc;

    public MemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增在组关系（同批次同页唯一约束保护）。 */
    public int insert(long batchId, long pageId, LocalDateTime joinedAt, String requestId) {
        return jdbc.update(
                "INSERT INTO batch_member (batch_id, page_id, member_status, joined_at, request_id) "
                        + "VALUES (?, ?, 'ACTIVE', ?, ?)",
                batchId, pageId, Timestamp.valueOf(joinedAt), requestId);
    }

    /** 此前已归还的成员重新入组同一批次：复用原行并置回 ACTIVE。 */
    public int reactivate(long batchId, long pageId, LocalDateTime joinedAt, String requestId) {
        return jdbc.update(
                "UPDATE batch_member SET member_status = 'ACTIVE', joined_at = ?, released_at = NULL, "
                        + "request_id = ? WHERE batch_id = ? AND page_id = ? AND member_status = 'RELEASED'",
                Timestamp.valueOf(joinedAt), requestId, batchId, pageId);
    }

    /** 按批次与页查询在组关系。 */
    public Optional<BatchMember> find(long batchId, long pageId) {
        List<BatchMember> list = jdbc.query(
                "SELECT id, batch_id, page_id, member_status, joined_at, released_at, request_id "
                        + "FROM batch_member WHERE batch_id = ? AND page_id = ?",
                MAPPER, batchId, pageId);
        return list.stream().findFirst();
    }

    /** 查询某页当前所有 ACTIVE 在组关系（正常最多一条）。 */
    public List<BatchMember> findActiveByPage(long pageId) {
        return jdbc.query(
                "SELECT id, batch_id, page_id, member_status, joined_at, released_at, request_id "
                        + "FROM batch_member WHERE page_id = ? AND member_status = 'ACTIVE'",
                MAPPER, pageId);
    }

    /** 查询批次内全部在组（ACTIVE）成员。 */
    public List<BatchMember> findActiveByBatch(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, page_id, member_status, joined_at, released_at, request_id "
                        + "FROM batch_member WHERE batch_id = ? AND member_status = 'ACTIVE'",
                MAPPER, batchId);
    }

    /** 从未封存批次归还：在组行置 RELEASED，仅当前确为 ACTIVE 时生效（返回受影响行数）。 */
    public int markReleased(long batchId, long pageId, LocalDateTime releasedAt) {
        return jdbc.update(
                "UPDATE batch_member SET member_status = 'RELEASED', released_at = ? "
                        + "WHERE batch_id = ? AND page_id = ? AND member_status = 'ACTIVE'",
                Timestamp.valueOf(releasedAt), batchId, pageId);
    }

    /** 查询批次内全部成员（含已归还），按入组时间排序。 */
    public List<BatchMember> findAllByBatch(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, page_id, member_status, joined_at, released_at, request_id "
                        + "FROM batch_member WHERE batch_id = ? ORDER BY joined_at, id",
                MAPPER, batchId);
    }
}
