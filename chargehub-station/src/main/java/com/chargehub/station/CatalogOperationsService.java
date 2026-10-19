package com.chargehub.station;

import com.chargehub.infrastructure.BusinessException;
import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Json;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chargehub.infrastructure.Db.*;

/** Catalogue changes lock stations before connectors; active bindings are never released here. */
@Service
public class CatalogOperationsService {
    private final CatalogRepository repository;
    private final CatalogCache cache;
    private final Validator validator;
    private final Db db;

    public CatalogOperationsService(CatalogRepository repository, CatalogCache cache, Validator validator, Db db) {
        this.repository = repository;
        this.cache = cache;
        this.validator = validator;
        this.db = db;
    }

    public Map<String, Object> stations(Long cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor < 1)) throw invalid("分页参数无效");
        var found = repository.stations(cursor, limit + 1);
        var rows = found.subList(0, Math.min(limit, found.size()));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", rows);
        page.put("nextCursor", found.size() > limit ? str(rows.getLast(), "id") : null);
        return page;
    }

    public List<Map<String, Object>> connectors(long stationId) {
        requireStation(stationId, false);
        return repository.stationConnectors(stationId, false);
    }

    @Transactional
    public Map<String, Object> updateTariff(long operator, long id, CatalogCommands.TariffInput input) {
        validate(input, input == null ? null : input.reason());
        var hint = db.one("SELECT station_id FROM tariff_plan WHERE id=?", id);
        if (hint == null) throw new BusinessException(404, "TARIFF_NOT_FOUND", "费率不存在");
        requireStation(num(hint, "station_id"), true);
        var before = db.one("SELECT * FROM tariff_plan WHERE id=? FOR UPDATE", id);
        if (!"DRAFT".equals(str(before, "status"))) {
            throw new BusinessException(409, "TARIFF_IMMUTABLE", "已经发布的费率不可修改，请新增版本");
        }
        before.put("slots", db.rows("SELECT start_minute,end_minute,energy_rate_fen_per_kwh,service_rate_fen_per_kwh FROM tariff_slot WHERE tariff_id=? ORDER BY start_minute", id));
        var slots = TariffRules.canonicalSlots(Json.read(Json.write(input)).get("slots"), false);
        db.update("DELETE FROM tariff_slot WHERE tariff_id=?", id);
        for (var slot : slots) {
            db.update("INSERT INTO tariff_slot(tariff_id,start_minute,end_minute,energy_rate_fen_per_kwh,service_rate_fen_per_kwh) VALUES(?,?,?,?,?)",
                    id, slot.get("startMinute"), slot.get("endMinute"), slot.get("energyRateFenPerKwh"), slot.get("serviceRateFenPerKwh"));
        }
        // Publication recomputes the authoritative full rule hash, including station/timezone/version.
        db.update("UPDATE tariff_plan SET rule_hash=? WHERE id=? AND status='DRAFT'", Json.hash(Json.write(slots)), id);
        var after = new LinkedHashMap<>(db.one("SELECT * FROM tariff_plan WHERE id=?", id));
        after.put("slots", slots);
        repository.audit(operator, "TARIFF_DRAFT_UPDATE", "Tariff", id, input.reason(), before, after);
        cache.invalidateAfterCommit();
        return after;
    }

    @Transactional
    public Map<String, Object> createStation(long operator, CatalogCommands.StationInput input) {
        validate(input, input == null ? null : input.reason());
        long id = repository.createStation(input);
        var after = requireStation(id, false);
        repository.audit(operator, "STATION_CREATE", "Station", id, input.reason(), null, after);
        cache.invalidateAfterCommit();
        return after;
    }

    @Transactional
    public Map<String, Object> updateStation(long operator, long id, CatalogCommands.StationInput input) {
        validate(input, input == null ? null : input.reason());
        var before = requireStation(id, true);
        if (!input.timezone().equals(str(before, "timezone"))) throw invalid("站点业务时区创建后不可更改");
        repository.updateStation(id, input);
        var after = requireStation(id, false);
        repository.audit(operator, "STATION_UPDATE", "Station", id, input.reason(), before, after);
        cache.invalidateAfterCommit();
        return after;
    }

    @Transactional
    public Map<String, Object> stationStatus(long operator, long id, CatalogCommands.StatusInput input) {
        validate(input, input == null ? null : input.reason());
        var before = requireStation(id, true);
        var connectors = repository.stationConnectors(id, true);
        if (!input.enabled() && connectors.stream().anyMatch(c -> str(c, "active_order_no") != null
                || "CHARGING".equals(str(c, "status")))) {
            throw new BusinessException(409, "ACTIVE_SESSION_EXISTS", "站点存在绑定订单，请结束业务后再停用");
        }
        repository.stationStatus(id, input.enabled());
        var after = requireStation(id, false);
        repository.audit(operator, "STATION_STATUS", "Station", id, input.reason(), before, after);
        cache.invalidateAfterCommit();
        return after;
    }

    @Transactional
    public Map<String, Object> createConnector(long operator, CatalogCommands.ConnectorInput input) {
        validate(input, input == null ? null : input.reason());
        long stationId = stationId(input);
        var station = requireStation(stationId, true);
        long id = repository.createConnector(input, "ENABLED".equals(str(station, "status")));
        var after = requireConnector(id, false);
        repository.audit(operator, "CONNECTOR_CREATE", "Connector", id, input.reason(), null, after);
        cache.invalidateAfterCommit();
        return after;
    }

    @Transactional
    public Map<String, Object> updateConnector(long operator, long id, CatalogCommands.ConnectorInput input) {
        validate(input, input == null ? null : input.reason());
        var hint = requireConnector(id, false);
        long originalStation = num(hint, "station_id"), targetStation = stationId(input);
        requireStation(Math.min(originalStation, targetStation), true);
        if (targetStation != originalStation) requireStation(Math.max(originalStation, targetStation), true);
        var before = requireConnector(id, true);
        if (num(before, "station_id") != originalStation) {
            throw new BusinessException(409, "CONNECTOR_CHANGED", "连接器配置已变更，请重新读取后重试");
        }
        requireIdle(before);
        var station = requireStation(targetStation, false);
        repository.updateConnector(id, input, "ENABLED".equals(str(station, "status")));
        var after = requireConnector(id, false);
        repository.audit(operator, "CONNECTOR_UPDATE", "Connector", id, input.reason(), before, after);
        cache.invalidateAfterCommit();
        return after;
    }

    @Transactional
    public Map<String, Object> connectorStatus(long operator, long id, CatalogCommands.StatusInput input) {
        validate(input, input == null ? null : input.reason());
        var hint = requireConnector(id, false);
        var station = requireStation(num(hint, "station_id"), true);
        var before = requireConnector(id, true);
        if (num(before, "station_id") != num(station, "id")) {
            throw new BusinessException(409, "CONNECTOR_CHANGED", "连接器归属已变更，请重新读取后重试");
        }
        requireIdle(before);
        repository.connectorStatus(id, input.enabled(), "ENABLED".equals(str(station, "status")));
        var after = requireConnector(id, false);
        repository.audit(operator, "CONNECTOR_STATUS", "Connector", id, input.reason(), before, after);
        cache.invalidateAfterCommit();
        return after;
    }

    private static void requireIdle(Map<String, Object> connector) {
        if (str(connector, "active_order_no") != null || "CHARGING".equals(str(connector, "status"))) {
            throw new BusinessException(409, "ACTIVE_SESSION_EXISTS", "连接器存在绑定订单或仍在充电，不能改变配置");
        }
    }

    private Map<String, Object> requireStation(long id, boolean lock) {
        var row = repository.station(id, lock);
        if (row == null) throw new BusinessException(404, "STATION_NOT_FOUND", "站点不存在");
        return row;
    }

    private Map<String, Object> requireConnector(long id, boolean lock) {
        var row = repository.connector(id, lock);
        if (row == null) throw new BusinessException(404, "CONNECTOR_NOT_FOUND", "连接器不存在");
        return row;
    }

    private <T> void validate(T input, String reason) {
        if (input == null || !validator.validate(input).isEmpty() || reason == null || reason.trim().length() < 5) {
            throw invalid("运营参数不合法，操作原因至少需要5个字符");
        }
    }

    private static long stationId(CatalogCommands.ConnectorInput input) {
        try { return Long.parseLong(input.stationId()); }
        catch (NumberFormatException error) { throw invalid("站点编号超出范围"); }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, "INVALID_REQUEST", message);
    }
}
