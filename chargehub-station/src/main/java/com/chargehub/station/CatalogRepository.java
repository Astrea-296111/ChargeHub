package com.chargehub.station;

import com.chargehub.infrastructure.Db;
import com.chargehub.infrastructure.Json;
import org.slf4j.MDC;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Parameterized persistence and audit boundary for catalogue administration. */
@Repository
public class CatalogRepository {
    private final Db db;
    public CatalogRepository(Db db) { this.db = db; }

    public Map<String, Object> station(long id, boolean lock) {
        return db.one("SELECT * FROM station WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    public Map<String, Object> connector(long id, boolean lock) {
        return db.one("SELECT * FROM connector WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    public List<Map<String, Object>> stationConnectors(long id, boolean lock) {
        return db.rows("SELECT * FROM connector WHERE station_id=? ORDER BY id" + (lock ? " FOR UPDATE" : ""), id);
    }

    public List<Map<String, Object>> stations(Long cursor, int limit) {
        return db.rows("SELECT * FROM station WHERE id>? ORDER BY id LIMIT ?", cursor == null ? 0L : cursor, limit);
    }

    public long createStation(CatalogCommands.StationInput input) {
        return db.insert("INSERT INTO station(station_code,name,address,timezone) VALUES(?,?,?,?)",
                input.stationCode(), input.name(), input.address(), input.timezone());
    }

    public void updateStation(long id, CatalogCommands.StationInput input) {
        db.update("UPDATE station SET station_code=?,name=?,address=? WHERE id=?",
                input.stationCode(), input.name(), input.address(), id);
    }

    public long createConnector(CatalogCommands.ConnectorInput input, boolean stationEnabled) {
        return db.insert("INSERT INTO connector(station_id,device_id,connector_code,status) VALUES(?,?,?,?)",
                input.stationId(), input.deviceId(), input.connectorCode(), stationEnabled ? "IDLE" : "DISABLED");
    }

    public void updateConnector(long id, CatalogCommands.ConnectorInput input, boolean stationEnabled) {
        var before = connector(id, false); // Caller already holds this connector's FOR UPDATE lock.
        boolean identityChanged = !input.deviceId().equals(Db.str(before, "device_id"))
                || !input.connectorCode().equals(Db.str(before, "connector_code"));
        String deviceState = safeDeviceState(before);
        String status = identityChanged ? "OFFLINE"
                : List.of("FAULT", "OFFLINE").contains(deviceState) ? deviceState
                : !stationEnabled || Db.num(before, "operational_enabled") != 1 ? "DISABLED" : deviceState;
        db.update("UPDATE connector SET station_id=?,device_id=?,connector_code=?,status=?,reported_device_status=CASE WHEN ?=1 THEN NULL ELSE reported_device_status END,reported_status_at=CASE WHEN ?=1 THEN NULL ELSE reported_status_at END,last_seen_at=CASE WHEN ?=1 THEN NULL ELSE last_seen_at END,version=version+1 WHERE id=?",
                input.stationId(), input.deviceId(), input.connectorCode(), status,
                identityChanged ? 1 : 0, identityChanged ? 1 : 0, identityChanged ? 1 : 0, id);
    }

    public void stationStatus(long id, boolean enabled) {
        db.update("UPDATE station SET status=? WHERE id=?", enabled ? "ENABLED" : "DISABLED", id);
        if (enabled) {
            db.update("UPDATE connector SET status=CASE WHEN reported_device_status IN ('FAULT','OFFLINE') THEN reported_device_status ELSE 'IDLE' END,version=version+1 WHERE station_id=? AND operational_enabled=1 AND status='DISABLED' AND active_order_no IS NULL", id);
        } else {
            db.update("UPDATE connector SET status=CASE WHEN status IN ('FAULT','OFFLINE') THEN status ELSE 'DISABLED' END,version=version+1 WHERE station_id=? AND active_order_no IS NULL", id);
        }
    }

    public void connectorStatus(long id, boolean enabled, boolean stationEnabled) {
        var before = connector(id, false);
        String deviceState = safeDeviceState(before);
        String status = (enabled && stationEnabled) || List.of("FAULT", "OFFLINE").contains(deviceState) ? deviceState : "DISABLED";
        db.update("UPDATE connector SET operational_enabled=?,status=?,version=version+1 WHERE id=? AND active_order_no IS NULL",
                enabled ? 1 : 0, status, id);
    }

    private static String safeDeviceState(Map<String, Object> connector) {
        String reported = Db.str(connector, "reported_device_status");
        if ("FAULT".equals(reported) || "OFFLINE".equals(reported)) return reported;
        String current = Db.str(connector, "status");
        return List.of("FAULT", "OFFLINE", "CHARGING").contains(current) ? current : "IDLE";
    }

    public void audit(long operator, String action, String kind, long id, String reason, Object before, Object after) {
        String requestId = MDC.get("requestId");
        db.update("INSERT INTO ops_audit(operator_id,action,target_type,target_no,reason,before_json,after_json,request_id) VALUES(?,?,?,?,?,?,?,?)",
                operator, action, kind, String.valueOf(id), reason, before == null ? null : Json.write(before),
                Json.write(after), requestId == null ? UUID.randomUUID().toString() : requestId);
    }
}
