package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareVersion;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 固件版本登记数据访问。firmware_version 行锁（SELECT ... FOR UPDATE）
 * 串行化同一固件版本的证书链登记、轮换与撤销裁决。
 */
@Repository
public class FirmwareVersionRepository {

    private final JdbcTemplate jdbc;

    public FirmwareVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String model, String version, String operator) throws DuplicateKeyException {
        jdbc.update("INSERT INTO firmware_version (model, version, created_by) VALUES (?, ?, ?)",
                model, version, operator);
    }

    public Optional<FirmwareVersion> find(String model, String version) {
        return jdbc.query("SELECT model, version, created_by FROM firmware_version"
                        + " WHERE model = ? AND version = ?",
                (rs, n) -> new FirmwareVersion(rs.getString("model"), rs.getString("version"),
                        rs.getString("created_by")), model, version).stream().findFirst();
    }

    /**
     * 锁定固件版本登记行；不存在时返回空。持有该锁期间读取/变更证书链，轮换与撤销按提交顺序裁决。
     */
    public Optional<FirmwareVersion> findForUpdate(String model, String version) {
        return jdbc.query("SELECT model, version, created_by FROM firmware_version"
                        + " WHERE model = ? AND version = ? FOR UPDATE",
                (rs, n) -> new FirmwareVersion(rs.getString("model"), rs.getString("version"),
                        rs.getString("created_by")), model, version).stream().findFirst();
    }
}
