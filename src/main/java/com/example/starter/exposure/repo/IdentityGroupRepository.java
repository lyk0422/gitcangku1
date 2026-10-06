package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.IdentityGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 身份归并组数据访问。加锁方法必须在事务内调用；
 * 归并与申请对成员关系的读写都先取 {@link #lockGlobal()} 单行锁，保证组账串行。
 */
@Repository
public class IdentityGroupRepository {

    private static final RowMapper<IdentityGroup> GROUP_MAPPER = (rs, rowNum) -> new IdentityGroup(
            rs.getString("group_id"),
            rs.getInt("version"),
            rs.getLong("created_at_utc"));

    private final JdbcTemplate jdbc;

    public IdentityGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 获取身份串行单行锁（SELECT ... FOR UPDATE），归并与申请共用。 */
    public void lockGlobal() {
        jdbc.query("SELECT lock_id FROM identity_union_lock WHERE lock_id = 1 FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null);
    }

    /** 查询访客所属组编号（无锁）；未入组返回 empty。 */
    public Optional<String> findGroupIdByVisitor(String visitorId) {
        List<String> list = jdbc.query(
                "SELECT group_id FROM identity_group_member WHERE visitor_id = ?",
                (rs, rowNum) -> rs.getString(1), visitorId);
        return list.stream().findFirst();
    }

    /** 行锁查询访客所属组编号；未入组返回 empty。 */
    public Optional<String> lockGroupIdByVisitor(String visitorId) {
        List<String> list = jdbc.query(
                "SELECT group_id FROM identity_group_member WHERE visitor_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), visitorId);
        return list.stream().findFirst();
    }

    /** 行锁读取归并组；不存在返回 empty。 */
    public Optional<IdentityGroup> lockGroupById(String groupId) {
        List<IdentityGroup> list = jdbc.query(
                "SELECT group_id, version, created_at_utc FROM identity_group "
                        + "WHERE group_id = ? FOR UPDATE",
                GROUP_MAPPER, groupId);
        return list.stream().findFirst();
    }

    /** 组内成员编号，按字典序升序（固定顺序，便于确定性加锁与响应）。 */
    public List<String> findMembers(String groupId) {
        return jdbc.query(
                "SELECT visitor_id FROM identity_group_member WHERE group_id = ? ORDER BY visitor_id",
                (rs, rowNum) -> rs.getString(1), groupId);
    }

    /** 组内成员数。 */
    public int countMembers(String groupId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_group_member WHERE group_id = ?",
                Integer.class, groupId);
        return count == null ? 0 : count;
    }

    public void insertGroup(IdentityGroup group) {
        jdbc.update("INSERT INTO identity_group (group_id, version, created_at_utc) VALUES (?, ?, ?)",
                group.groupId(), group.version(), group.createdAtUtc());
    }

    public void insertMember(String groupId, String visitorId, long joinedAtUtc) {
        jdbc.update("INSERT INTO identity_group_member (group_id, visitor_id, joined_at_utc) "
                + "VALUES (?, ?, ?)", groupId, visitorId, joinedAtUtc);
    }

    /** 将源组全部成员并入目标组（源组随后由 {@link #deleteGroup} 删除）。 */
    public void moveMembers(String fromGroupId, String toGroupId) {
        jdbc.update("UPDATE identity_group_member SET group_id = ? WHERE group_id = ?",
                toGroupId, fromGroupId);
    }

    public void deleteGroup(String groupId) {
        jdbc.update("DELETE FROM identity_group WHERE group_id = ?", groupId);
    }

    /** 成员变更后版本号 +1。 */
    public void incrementVersion(String groupId) {
        int rows = jdbc.update("UPDATE identity_group SET version = version + 1 WHERE group_id = ?",
                groupId);
        if (rows != 1) {
            throw new IllegalStateException("identity group row missing for " + groupId);
        }
    }
}
