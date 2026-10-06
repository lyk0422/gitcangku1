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
 * 封存箱表访问。树级写操作必须先通过 {@link #findByKeyForUpdate} 锁定根箱行，
 * 保证装箱/拆箱/整箱交接/异常登记按事务提交顺序生效。
 */
@Repository
public class ContainerRepository {

    private static final ContainerRowMapper ROW_MAPPER = new ContainerRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新根箱：交接状态 SEALED，封条 INTACT，版本从 1 开始。
     */
    public void insert(String containerKey, String sealNo, String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container
                            (container_key, seal_no, custodian_id, status, seal_status, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                        """,
                containerKey, sealNo, custodianId,
                ContainerStatus.SEALED.name(), SealStatus.INTACT.name(), now, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景。
     */
    public Optional<Container> findByKey(String containerKey) {
        List<Container> rows = jdbc.query(
                "SELECT * FROM container WHERE container_key = ?", ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定箱行（SELECT ... FOR UPDATE），用于一切树级变更。
     */
    public Optional<Container> findByKeyForUpdate(String containerKey) {
        List<Container> rows = jdbc.query(
                "SELECT * FROM container WHERE container_key = ? FOR UPDATE", ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 批量更新一批箱（整棵树）的交接状态。
     */
    public void updateStatus(List<String> containerKeys, ContainerStatus status, LocalDateTime now) {
        String placeholders = String.join(", ", Collections.nCopies(containerKeys.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(status.name());
        args.add(now);
        args.addAll(containerKeys);
        jdbc.update("UPDATE container SET status = ?, updated_at = ? WHERE container_key IN ("
                + placeholders + ")", args.toArray());
    }

    /**
     * 批量更新一批箱（整棵树）的保管人与交接状态（整箱交接接受时原子切换）。
     */
    public void updateCustody(List<String> containerKeys, String custodianId,
                              ContainerStatus status, LocalDateTime now) {
        String placeholders = String.join(", ", Collections.nCopies(containerKeys.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(custodianId);
        args.add(status.name());
        args.add(now);
        args.addAll(containerKeys);
        jdbc.update("UPDATE container SET custodian_id = ?, status = ?, updated_at = ? "
                + "WHERE container_key IN (" + placeholders + ")", args.toArray());
    }

    /**
     * 根箱版本加一（成员变更时调用，调用前必须已校验 expectedVersion 并锁定根箱行）。
     */
    public void incrementVersion(String containerKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE container SET version = version + 1, updated_at = ? WHERE container_key = ?",
                now, containerKey);
    }

    /**
     * 更新箱独立封条状态（核验失败置 BROKEN，终态不可恢复）。
     */
    public void updateSealStatus(String containerKey, SealStatus sealStatus, LocalDateTime now) {
        jdbc.update(
                "UPDATE container SET seal_status = ?, updated_at = ? WHERE container_key = ?",
                sealStatus.name(), now, containerKey);
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
                    SealStatus.valueOf(rs.getString("seal_status")),
                    rs.getInt("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
