package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.IdentityGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 身份归并数据访问：归并组与访客-组归属。加锁方法必须在事务内调用，
 * 依赖 SELECT ... FOR UPDATE 行锁使归并与申请形成串行组账。
 */
@Repository
public class IdentityRepository {

    private static final RowMapper<IdentityGroup> MAPPER = (rs, rowNum) -> new IdentityGroup(
            rs.getString("group_id"),
            rs.getInt("version"),
            rs.getInt("member_count"),
            rs.getString("last_operator"),
            rs.getLong("updated_at_utc"));

    private static final String COLUMNS =
            "group_id, version, member_count, last_operator, updated_at_utc";

    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询访客所属归并组；未归并返回 empty（视为单成员组，版本 0）。 */
    public Optional<IdentityGroup> findGroupByVisitor(String visitorId) {
        List<IdentityGroup> list = jdbc.query(
                "SELECT g.group_id, g.version, g.member_count, g.last_operator, g.updated_at_utc "
                        + "FROM identity_group g "
                        + "JOIN visitor_identity m ON m.group_id = g.group_id "
                        + "WHERE m.visitor_id = ?",
                MAPPER, visitorId);
        return list.stream().findFirst();
    }

    /** 行锁读取归并组；不存在返回 empty。 */
    public Optional<IdentityGroup> lockGroupById(String groupId) {
        List<IdentityGroup> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM identity_group WHERE group_id = ? FOR UPDATE",
                MAPPER, groupId);
        return list.stream().findFirst();
    }

    /** 组内全部成员编号，按编号升序（加锁与分账的确定顺序）。 */
    public List<String> listMembers(String groupId) {
        return jdbc.query("SELECT visitor_id FROM visitor_identity WHERE group_id = ? "
                        + "ORDER BY visitor_id",
                (rs, rowNum) -> rs.getString(1), groupId);
    }

    public void insertGroup(IdentityGroup group) {
        jdbc.update("INSERT INTO identity_group (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                group.groupId(),
                group.version(),
                group.memberCount(),
                group.lastOperator(),
                group.updatedAtUtc());
    }

    /** 归并后更新组版本、成员数、操作者与时刻。 */
    public void updateGroupAfterMerge(String groupId, int version, int memberCount,
                                      String operator, long updatedAtUtc) {
        int rows = jdbc.update("UPDATE identity_group "
                        + "SET version = ?, member_count = ?, last_operator = ?, updated_at_utc = ? "
                        + "WHERE group_id = ?",
                version, memberCount, operator, updatedAtUtc, groupId);
        if (rows != 1) {
            throw new IllegalStateException("identity group missing: " + groupId);
        }
    }

    public void deleteGroup(String groupId) {
        jdbc.update("DELETE FROM identity_group WHERE group_id = ?", groupId);
    }

    /** 将访客加入指定组；visitor_id 唯一约束冲突时抛 DuplicateKeyException。 */
    public void insertMember(String visitorId, String groupId) {
        jdbc.update("INSERT INTO visitor_identity (visitor_id, group_id) VALUES (?, ?)",
                visitorId, groupId);
    }

    /** 将访客从当前组移动到目标组。 */
    public void moveMember(String visitorId, String targetGroupId) {
        int rows = jdbc.update("UPDATE visitor_identity SET group_id = ? WHERE visitor_id = ?",
                targetGroupId, visitorId);
        if (rows != 1) {
            throw new IllegalStateException("visitor identity missing: " + visitorId);
        }
    }
}
