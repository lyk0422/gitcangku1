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
 * 箱成员表访问。(member_type, member_key) 全局唯一约束保证任一对象最多一个直接父箱；
 * 成员变更必须先锁定所属根箱行。
 */
@Repository
public class ContainerMemberRepository {

    private static final MemberRowMapper ROW_MAPPER = new MemberRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条成员关系（装箱）。
     */
    public void insert(String containerKey, String memberType, String memberKey, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_member (container_key, member_type, member_key, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                containerKey, memberType, memberKey, now);
    }

    /**
     * 移除一条成员关系（拆箱）。
     */
    public void delete(String containerKey, String memberType, String memberKey) {
        jdbc.update(
                "DELETE FROM container_member WHERE container_key = ? AND member_type = ? AND member_key = ?",
                containerKey, memberType, memberKey);
    }

    /**
     * 查询对象当前的成员关系（任一对象最多一条，即唯一直接父箱）。
     */
    public Optional<ContainerMember> findByMember(String memberType, String memberKey) {
        List<ContainerMember> rows = jdbc.query(
                "SELECT * FROM container_member WHERE member_type = ? AND member_key = ?",
                ROW_MAPPER, memberType, memberKey);
        return rows.stream().findFirst();
    }

    /**
     * 按装箱顺序查询箱的直接成员。
     */
    public List<ContainerMember> findByContainerKey(String containerKey) {
        return jdbc.query(
                "SELECT * FROM container_member WHERE container_key = ? ORDER BY id",
                ROW_MAPPER, containerKey);
    }

    /**
     * 查询一组箱的全部直接成员（整树展开用）。
     */
    public List<ContainerMember> findByContainerKeys(List<String> containerKeys) {
        if (containerKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", containerKeys.stream().map(k -> "?").toList());
        return jdbc.query(
                "SELECT * FROM container_member WHERE container_key IN (" + placeholders + ") ORDER BY id",
                ROW_MAPPER, containerKeys.toArray());
    }

    /**
     * 统计一组箱内的证物成员数量（根箱最多 10 件证物约束）。
     */
    public int countEvidenceMembers(List<String> containerKeys) {
        if (containerKeys.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", containerKeys.stream().map(k -> "?").toList());
        Object[] args = new Object[containerKeys.size() + 1];
        containerKeys.toArray(args);
        args[containerKeys.size()] = ContainerMember.TYPE_EVIDENCE;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM container_member WHERE container_key IN (" + placeholders + ")"
                        + " AND member_type = ?",
                Integer.class, args);
        return count == null ? 0 : count;
    }

    private static final class MemberRowMapper implements RowMapper<ContainerMember> {
        @Override
        public ContainerMember mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerMember(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("member_type"),
                    rs.getString("member_key"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
