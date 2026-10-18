package com.chargehub.station;

import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chargehub.infrastructure.Db.*;

@Service
public class StationService {
    private final Db db;
    private final Clock clock;

    public StationService(Db db, Clock clock) { this.db = db; this.clock = clock; }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> station : db.rows("SELECT s.*,t.version_no FROM station s LEFT JOIN tariff_plan t ON t.id=s.active_tariff_id WHERE s.status='ENABLED' ORDER BY s.id LIMIT 100")) {
            result.add(station(station));
        }
        return result;
    }

    /** Cursor is the last returned monotonic station ID; pages traverse IDs ascending. */
    public Map<String, Object> list(Long cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor < 1)) {
            throw new BusinessException(400, "INVALID_REQUEST", "分页数量须为1至100，游标须为有效正整数");
        }
        var rows = db.rows("SELECT s.*,t.version_no FROM station s LEFT JOIN tariff_plan t ON t.id=s.active_tariff_id WHERE s.status='ENABLED' AND s.id>? ORDER BY s.id LIMIT ?",
                cursor == null ? 0L : cursor, limit + 1);
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(rows.size(), limit));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", selected.stream().map(this::station).toList());
        page.put("nextCursor", more ? str(selected.getLast(), "id") : null);
        return page;
    }

    public Map<String, Object> get(long stationId) {
        Map<String, Object> row = db.one("SELECT s.*,t.version_no FROM station s LEFT JOIN tariff_plan t ON t.id=s.active_tariff_id WHERE s.id=? AND s.status='ENABLED'", stationId);
        if (row == null) throw new BusinessException(404, "STATION_NOT_FOUND", "站点不存在");
        return station(row);
    }

    public Map<String, Object> connector(long connectorId) {
        Map<String, Object> row = db.one("SELECT * FROM connector WHERE id=?", connectorId);
        if (row == null) throw new BusinessException(404, "CONNECTOR_NOT_FOUND", "充电枪不存在");
        return connectorView(row);
    }

    private Map<String, Object> station(Map<String, Object> row) {
        List<Map<String, Object>> connectors = db.rows("SELECT * FROM connector WHERE station_id=? ORDER BY id", num(row, "id"))
                .stream().map(this::connectorView).toList();
        return Map.of("stationId", str(row, "id"), "name", str(row, "name"), "address", str(row, "address"),
                "timezone", str(row, "timezone"), "tariffVersion", num(row, "version_no"), "connectors", connectors);
    }

    private Map<String, Object> connectorView(Map<String, Object> row) {
        return Map.of("connectorId", str(row, "id"), "deviceId", str(row, "device_id"),
                "connectorCode", str(row, "connector_code"), "status", str(row, "status"),
                "available", "IDLE".equals(str(row, "status")) && str(row, "active_order_no") == null);
    }

    /** Version is checked against the active published version, never a client supplied price. */
    public Map<String, Object> tariff(long connectorId, Long expectedVersion) {
        Map<String, Object> row = db.one("SELECT s.id station_id,s.timezone,s.status station_status,t.id tariff_id,t.version_no,t.status,t.rule_hash FROM connector c JOIN station s ON s.id=c.station_id LEFT JOIN tariff_plan t ON t.id=s.active_tariff_id WHERE c.id=?", connectorId);
        if (row == null) throw new BusinessException(404, "CONNECTOR_NOT_FOUND", "充电枪不存在");
        if (!"ENABLED".equals(str(row, "station_status")) || !"PUBLISHED".equals(str(row, "status"))) {
            throw new BusinessException(409, "TARIFF_UNAVAILABLE", "站点没有可用的已发布费率");
        }
        if (expectedVersion != null && expectedVersion != num(row, "version_no")) {
            throw new BusinessException(409, "TARIFF_CHANGED", "费率版本已更新，请重新确认");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tariffId", str(row, "tariff_id"));
        snapshot.put("stationId", str(row, "station_id"));
        snapshot.put("tariffVersion", num(row, "version_no"));
        snapshot.put("timezone", str(row, "timezone"));
        snapshot.put("ruleHash", str(row, "rule_hash"));
        snapshot.put("slots", slots(num(row, "tariff_id")));
        return snapshot;
    }

    public Map<String, Object> validatePublished(long connectorId, Long expectedVersion) {
        return tariff(connectorId, expectedVersion);
    }

    @Transactional
    public Map<String, Object> createTariff(Map<String, Object> request) {
        long stationId = positive(request.get("stationId"));
        long requestedVersion = positive(request.get("versionNo"));
        if (requestedVersion > Integer.MAX_VALUE) throw new BusinessException(400, "INVALID_REQUEST", "费率版本超出范围");
        int version = (int) requestedVersion;
        var slots = TariffRules.canonicalSlots(request.get("slots"), false);
        Map<String, Object> station = db.one("SELECT id,timezone FROM station WHERE id=? FOR UPDATE", stationId);
        if (station == null) throw new BusinessException(404, "STATION_NOT_FOUND", "站点不存在");
        if (db.one("SELECT id FROM tariff_plan WHERE station_id=? AND version_no=? FOR UPDATE", stationId, version) != null) {
            throw new BusinessException(409, "TARIFF_VERSION_EXISTS", "费率版本已存在");
        }
        long id = db.insert("INSERT INTO tariff_plan(station_id,version_no,rule_hash) VALUES(?,?,?)", stationId, version,
                hash(stationId, version, str(station, "timezone"), slots));
        for (Map<String, Object> slot : slots) {
            db.update("INSERT INTO tariff_slot(tariff_id,start_minute,end_minute,energy_rate_fen_per_kwh,service_rate_fen_per_kwh) VALUES(?,?,?,?,?)",
                    id, slot.get("startMinute"), slot.get("endMinute"), slot.get("energyRateFenPerKwh"), slot.get("serviceRateFenPerKwh"));
        }
        return tariffView(id);
    }

    @Transactional
    public Map<String, Object> publishTariff(long tariffId) {
        Map<String, Object> found = db.one("SELECT station_id FROM tariff_plan WHERE id=?", tariffId);
        if (found == null) throw new BusinessException(404, "TARIFF_NOT_FOUND", "费率不存在");
        // The station lock serializes all version publishing and prevents a stale active pointer.
        Map<String, Object> station = db.one("SELECT * FROM station WHERE id=? FOR UPDATE", num(found, "station_id"));
        Map<String, Object> tariff = db.one("SELECT * FROM tariff_plan WHERE id=? FOR UPDATE", tariffId);
        if ("PUBLISHED".equals(str(tariff, "status"))) return tariffView(tariffId);
        var slots = TariffRules.canonicalSlots(slots(tariffId), true);
        Map<String, Object> active = db.one("SELECT version_no FROM tariff_plan WHERE id=?", num(station, "active_tariff_id"));
        if (active != null && num(tariff, "version_no") <= num(active, "version_no")) {
            throw new BusinessException(409, "TARIFF_VERSION_STALE", "发布版本必须高于当前版本");
        }
        db.update("UPDATE tariff_plan SET status='PUBLISHED',published_at=?,rule_hash=? WHERE id=? AND status='DRAFT'",
                clock.instant(), hash(num(tariff, "station_id"), num(tariff, "version_no"), str(station, "timezone"), slots), tariffId);
        db.update("UPDATE station SET active_tariff_id=? WHERE id=?", tariffId, num(tariff, "station_id"));
        return tariffView(tariffId);
    }

    public Map<String, Object> tariffView(long id) {
        Map<String, Object> row = db.one("SELECT * FROM tariff_plan WHERE id=?", id);
        if (row == null) throw new BusinessException(404, "TARIFF_NOT_FOUND", "费率不存在");
        return Map.of("tariffId", str(row, "id"), "stationId", str(row, "station_id"), "versionNo", num(row, "version_no"),
                "status", str(row, "status"), "ruleHash", str(row, "rule_hash"), "slots", slots(id));
    }

    private List<Map<String, Object>> slots(long tariffId) {
        return db.rows("SELECT * FROM tariff_slot WHERE tariff_id=? ORDER BY start_minute", tariffId).stream().map(row -> {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("startMinute", num(row, "start_minute"));
            slot.put("endMinute", num(row, "end_minute"));
            slot.put("energyRateFenPerKwh", num(row, "energy_rate_fen_per_kwh"));
            slot.put("serviceRateFenPerKwh", num(row, "service_rate_fen_per_kwh"));
            return slot;
        }).toList();
    }

    private static String hash(long station, long version, String timezone, List<Map<String, Object>> slots) {
        ZoneId.of(timezone);
        StringBuilder canonical = new StringBuilder(station + ":" + version + ":" + timezone);
        for (var slot : slots) canonical.append('|').append(slot.get("startMinute")).append(':').append(slot.get("endMinute"))
                .append(':').append(slot.get("energyRateFenPerKwh")).append(':').append(slot.get("serviceRateFenPerKwh"));
        return Json.hash(canonical.toString());
    }

    private static long positive(Object value) {
        try {
            long number = new BigDecimal(String.valueOf(value)).longValueExact();
            if (number <= 0) throw new ArithmeticException();
            return number;
        } catch (RuntimeException ex) {
            throw new BusinessException(400, "INVALID_REQUEST", "编号与版本须为有效正整数");
        }
    }
}
