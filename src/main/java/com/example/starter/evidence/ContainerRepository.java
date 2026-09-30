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
 * 容器表访问。容器状态与封条版本变更必须先通过 {@link #findByKeyForUpdate} 锁定容器行，
 * 使异常巡检确认与复核放行、并发巡检按事务提交顺序串行。
 */
@Repository
public class ContainerRepository {

    private static final ContainerRowMapper ROW_MAPPER = new ContainerRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记新容器：封条版本初始为 1，状态 ACTIVE，保管人为操作人。
     */
    public void insert(String containerKey, String location, String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container
                            (container_key, location, custodian_id, seal_version, status, created_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, ?, ?)
                        """,
                containerKey, location, custodianId, ContainerStatus.ACTIVE.name(), now, now);
    }

    /**
     * 按业务键查询容器（不加锁）。
     */
    public Optional<Container> findByKey(String containerKey) {
        List<Container> rows = jdbc.query(
                "SELECT * FROM container WHERE container_key = ?", ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定容器行（SELECT ... FOR UPDATE）。
     */
    public Optional<Container> findByKeyForUpdate(String containerKey) {
        List<Container> rows = jdbc.query(
                "SELECT * FROM container WHERE container_key = ? FOR UPDATE", ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 更新容器状态（异常双人确认后 QUARANTINED；复核放行恢复 ACTIVE）。
     */
    public void updateStatus(String containerKey, ContainerStatus status, LocalDateTime now) {
        jdbc.update(
                "UPDATE container SET status = ?, updated_at = ? WHERE container_key = ?",
                status.name(), now, containerKey);
    }

    /**
     * 更换封条：版本号递增 1（调用前必须已锁定容器行并校验 ACTIVE）。
     */
    public void bumpSealVersion(String containerKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE container SET seal_version = seal_version + 1, updated_at = ? WHERE container_key = ?",
                now, containerKey);
    }

    private static final class ContainerRowMapper implements RowMapper<Container> {
        @Override
        public Container mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Container(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("location"),
                    rs.getString("custodian_id"),
                    rs.getInt("seal_version"),
                    ContainerStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
