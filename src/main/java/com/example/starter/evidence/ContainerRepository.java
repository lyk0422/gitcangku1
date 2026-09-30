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
 * 容器表访问。版本与状态变更必须先通过 {@link #findByKeyForUpdate} 锁定容器行，
 * 保证巡检确认与放入/移出按事务提交顺序生效。
 */
@Repository
public class ContainerRepository {

    private static final ContainerRowMapper ROW_MAPPER = new ContainerRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建容器，初始版本 0、状态 NORMAL。
     */
    public void insert(String containerKey, String location, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container
                            (container_key, location, version, status, created_at, updated_at)
                        VALUES (?, ?, 0, ?, ?, ?)
                        """,
                containerKey, location, ContainerStatus.NORMAL.name(), now, now);
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
     * 按业务键查询并锁定容器行（SELECT ... FOR UPDATE），用于一切版本/状态变更。
     */
    public Optional<Container> findByKeyForUpdate(String containerKey) {
        List<Container> rows = jdbc.query(
                "SELECT * FROM container WHERE container_key = ? FOR UPDATE", ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 箱内证物放入/移出时版本自增（调用前必须已锁定容器行）。
     */
    public void bumpVersion(String containerKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE container SET version = version + 1, updated_at = ? WHERE container_key = ?",
                now, containerKey);
    }

    /**
     * 更新容器状态（异常隔离 / 复核放行；调用前必须已锁定容器行）。
     */
    public void updateStatus(String containerKey, ContainerStatus status, LocalDateTime now) {
        jdbc.update(
                "UPDATE container SET status = ?, updated_at = ? WHERE container_key = ?",
                status.name(), now, containerKey);
    }

    private static final class ContainerRowMapper implements RowMapper<Container> {
        @Override
        public Container mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Container(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("location"),
                    rs.getLong("version"),
                    ContainerStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
