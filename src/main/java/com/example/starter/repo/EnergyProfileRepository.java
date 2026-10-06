package com.example.starter.repo;

import com.example.starter.domain.EnergyProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 航线版本合成能量档案数据访问。
 * 档案绑定具体航线版本；航线修订生成新版本时旧版本档案整体替换。
 */
@Repository
public class EnergyProfileRepository {

    private final JdbcTemplate jdbc;

    public EnergyProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询指定航线版本的能量档案，不存在（旧契约航线）返回 null。 */
    public EnergyProfile findProfile(String routeId, int routeVersion) {
        record Header(long departureEnergy, long reserveEnergy) {
        }
        List<Header> headers = jdbc.query(
                "SELECT departure_energy, reserve_energy FROM route_energy_profile "
                        + "WHERE route_id = ? AND route_version = ?",
                (rs, n) -> new Header(rs.getLong("departure_energy"),
                        rs.getLong("reserve_energy")),
                routeId, routeVersion);
        if (headers.isEmpty()) {
            return null;
        }
        List<Long> segmentEnergies = jdbc.query(
                "SELECT energy FROM route_segment_energy "
                        + "WHERE route_id = ? AND route_version = ? ORDER BY seq",
                (rs, n) -> rs.getLong("energy"), routeId, routeVersion);
        List<Long> alternateEnergies = jdbc.query(
                "SELECT energy FROM route_alternate_energy "
                        + "WHERE route_id = ? AND route_version = ? ORDER BY point_seq",
                (rs, n) -> rs.getLong("energy"), routeId, routeVersion);
        Header header = headers.get(0);
        return new EnergyProfile(header.departureEnergy(), header.reserveEnergy(),
                segmentEnergies, alternateEnergies);
    }

    /** 写入指定航线版本的能量档案（调用方负责事务）。 */
    public void insertProfile(String routeId, int routeVersion,
                              EnergyProfile profile, String operator) {
        jdbc.update("INSERT INTO route_energy_profile "
                        + "(route_id, route_version, departure_energy, reserve_energy, operator) "
                        + "VALUES (?, ?, ?, ?, ?)",
                routeId, routeVersion, profile.departureEnergy(), profile.reserveEnergy(),
                operator);
        for (int i = 0; i < profile.segmentEnergies().size(); i++) {
            jdbc.update("INSERT INTO route_segment_energy "
                            + "(route_id, route_version, seq, energy) VALUES (?, ?, ?, ?)",
                    routeId, routeVersion, i, profile.segmentEnergies().get(i));
        }
        for (int i = 0; i < profile.alternateEnergies().size(); i++) {
            jdbc.update("INSERT INTO route_alternate_energy "
                            + "(route_id, route_version, point_seq, energy) VALUES (?, ?, ?, ?)",
                    routeId, routeVersion, i, profile.alternateEnergies().get(i));
        }
    }

    /** 删除航线全部历史版本的能量档案（航线修订时调用，调用方负责事务）。 */
    public void deleteProfiles(String routeId) {
        jdbc.update("DELETE FROM route_segment_energy WHERE route_id = ?", routeId);
        jdbc.update("DELETE FROM route_alternate_energy WHERE route_id = ?", routeId);
        jdbc.update("DELETE FROM route_energy_profile WHERE route_id = ?", routeId);
    }
}
