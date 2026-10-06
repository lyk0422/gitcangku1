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
 * 封存箱表访问。整树操作必须先锁定根箱行（SELECT ... FOR UPDATE），
 * 成员变更、交接发起/接受/取消与封条核验均按根箱行锁串行化。
 */
@Repository
public class ContainerRepository {

    private static final ContainerRowMapper ROW_MAPPER = new ContainerRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新根箱，初始状态 SEALED，版本 1，root_key 等于自身键。
     */
    public void insert(String containerKey, String sealNo, String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_container
                            (container_key, seal_no, custodian_id, status, version, parent_key, root_key,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, 1, NULL, ?, ?, ?)
                        """,
                containerKey, sealNo, custodianId,
                ContainerStatus.SEALED.name(), containerKey, now, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景与锁前解析。
     */
    public Optional<Container> findByKey(String containerKey) {
        List<Container> rows = jdbc.query(
                "SELECT * FROM evidence_container WHERE container_key = ?", ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定箱行（SELECT ... FOR UPDATE），用于一切树结构/状态变更。
     */
    public Optional<Container> findByKeyForUpdate(String containerKey) {
        List<Container> rows = jdbc.query(
                "SELECT * FROM evidence_container WHERE container_key = ? FOR UPDATE",
                ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询根箱整树全部箱（含根箱自身），按主键排序。
     */
    public List<Container> findByRootKey(String rootKey) {
        return jdbc.query(
                "SELECT * FROM evidence_container WHERE root_key = ? ORDER BY id",
                ROW_MAPPER, rootKey);
    }

    /**
     * 更新直接父箱（装箱挂接 / 拆箱置 NULL）。
     */
    public void updateParent(String containerKey, String parentKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence_container SET parent_key = ?, updated_at = ? WHERE container_key = ?",
                parentKey, now, containerKey);
    }

    /**
     * 批量更新一组箱的所属根箱（子树挂接或拆出时同步冗余列）。
     */
    public void updateRootKey(List<String> containerKeys, String rootKey, LocalDateTime now) {
        for (String key : containerKeys) {
            jdbc.update(
                    "UPDATE evidence_container SET root_key = ?, updated_at = ? WHERE container_key = ?",
                    rootKey, now, key);
        }
    }

    /**
     * 根箱成员版本条件递增：仅当当前版本等于 expectedVersion 时加一。
     *
     * @return 是否成功递增（false 表示版本不匹配，返回 409）
     */
    public boolean incrementVersion(String rootKey, int expectedVersion, LocalDateTime now) {
        int updated = jdbc.update(
                "UPDATE evidence_container SET version = version + 1, updated_at = ?"
                        + " WHERE container_key = ? AND version = ?",
                now, rootKey, expectedVersion);
        return updated == 1;
    }

    /**
     * 将根箱整树（仍为 SEALED 的箱）置为 TRANSFER_PENDING（交接发起冻结）。
     */
    public void freezeTree(String rootKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence_container SET status = ?, updated_at = ?"
                        + " WHERE root_key = ? AND status = ?",
                ContainerStatus.TRANSFER_PENDING.name(), now, rootKey,
                ContainerStatus.SEALED.name());
    }

    /**
     * 将根箱整树（仍为 TRANSFER_PENDING 的箱）置回 SEALED（交接取消；
     * 待接收期间被封条核验打坏的箱保持 SEAL_BROKEN 不变）。
     */
    public void unfreezeTree(String rootKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence_container SET status = ?, updated_at = ?"
                        + " WHERE root_key = ? AND status = ?",
                ContainerStatus.SEALED.name(), now, rootKey,
                ContainerStatus.TRANSFER_PENDING.name());
    }

    /**
     * 交接接受：整树保管人原子切换为接收人并回到 SEALED（仅仍冻结的箱）。
     */
    public void acceptTree(String rootKey, String toCustodian, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence_container SET custodian_id = ?, status = ?, updated_at = ?"
                        + " WHERE root_key = ? AND status = ?",
                toCustodian, ContainerStatus.SEALED.name(), now, rootKey,
                ContainerStatus.TRANSFER_PENDING.name());
    }

    /**
     * 仅更新箱状态（封条核验失败置 SEAL_BROKEN）。
     */
    public void updateStatus(String containerKey, ContainerStatus status, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence_container SET status = ?, updated_at = ? WHERE container_key = ?",
                status.name(), now, containerKey);
    }

    private static final class ContainerRowMapper implements RowMapper<Container> {
        @Override
        public Container mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Container(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("seal_no"),
                    rs.getString("custodian_id"),
                    ContainerStatus.valueOf(rs.getString("status")),
                    rs.getInt("version"),
                    rs.getString("parent_key"),
                    rs.getString("root_key"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
