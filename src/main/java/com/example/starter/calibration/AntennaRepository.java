package com.example.starter.calibration;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 天线主数据访问；提供按天线串行化绑定的行级锁。
 */
@Repository
public class AntennaRepository {

    private final JdbcTemplate jdbc;

    public AntennaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编号查询天线主键。 */
    public Optional<Long> findIdByCode(String antennaCode) {
        List<Long> rows = jdbc.query("SELECT id FROM antenna WHERE antenna_code = ?",
                (rs, n) -> rs.getLong("id"), antennaCode);
        return rows.stream().findFirst();
    }

    /** 按主键查询天线编号。 */
    public Optional<String> findCodeById(long antennaId) {
        List<String> rows = jdbc.query("SELECT antenna_code FROM antenna WHERE id = ?",
                (rs, n) -> rs.getString("antenna_code"), antennaId);
        return rows.stream().findFirst();
    }

    /** 对天线行加悲观写锁，串行化同一天线的绑定操作。 */
    public void lockById(long antennaId) {
        jdbc.query("SELECT id FROM antenna WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getLong("id"), antennaId);
    }
}