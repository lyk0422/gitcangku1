package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 容器成员表访问。(member_type, member_key) 唯一约束保证证物或子箱仅能有一个直接父箱；
 * 成员行的插入与删除必须在持有根箱行锁的事务内完成。
 */
@Repository
public class ContainerMemberRepository {

    private static final MemberRowMapper ROW_MAPPER = new MemberRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 装箱：追加一条成员记录；成员已有父箱时由唯一约束拒绝。
     */
    public void insert(String containerKey, MemberType memberType, String memberKey,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_member (container_key, member_type, member_key, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                containerKey, memberType.name(), memberKey, now);
    }

    /**
     * 拆箱：删除成员记录，成员恢复为无父箱的顶层对象。
     */
    public void delete(String containerKey, MemberType memberType, String memberKey) {
        jdbc.update(
                "DELETE FROM container_member WHERE container_key = ? AND member_type = ? AND member_key = ?",
                containerKey, memberType.name(), memberKey);
    }

    /**
     * 查询成员当前的直接父箱业务键；空表示该成员不在任何箱内。
     */
    public Optional<String> findParentKey(MemberType memberType, String memberKey) {
        List<String> rows = jdbc.query(
                "SELECT container_key FROM container_member WHERE member_type = ? AND member_key = ?",
                (rs, rowNum) -> rs.getString("container_key"), memberType.name(), memberKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询指定箱的直接成员（按装箱顺序）。
     */
    public List<ContainerMember> findByContainerKey(String containerKey) {
        return jdbc.query(
                "SELECT * FROM container_member WHERE container_key = ? ORDER BY id",
                ROW_MAPPER, containerKey);
    }

    /**
     * 批量查询一批箱的直接成员（用于整树收集与证物计数）。
     */
    public List<ContainerMember> findByContainerKeys(List<String> containerKeys) {
        if (containerKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(containerKeys.size(), "?"));
        return jdbc.query(
                "SELECT * FROM container_member WHERE container_key IN (" + placeholders + ") ORDER BY id",
                ROW_MAPPER, containerKeys.toArray());
    }

    private static final class MemberRowMapper implements RowMapper<ContainerMember> {
        @Override
        public ContainerMember mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerMember(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    MemberType.valueOf(rs.getString("member_type")),
                    rs.getString("member_key"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
