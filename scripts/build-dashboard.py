"""Generates infra/grafana/dashboards/surtido.json. Run after editing: python scripts/build-dashboard.py"""
import json
from pathlib import Path

DS = {"type": "prometheus", "uid": "prometheus"}
panels = []


def add(panel, x, y, w, h):
    panel.update({"id": len(panels) + 1, "datasource": DS, "gridPos": {"x": x, "y": y, "w": w, "h": h}})
    panels.append(panel)


def stat(title, expr, unit, x, y, w=6, decimals=0):
    add({"type": "stat", "title": title,
         "targets": [{"datasource": DS, "expr": expr, "refId": "A"}],
         "fieldConfig": {"defaults": {"unit": unit, "decimals": decimals, "color": {"mode": "fixed", "fixedColor": "text"}}},
         "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "graphMode": "area", "colorMode": "value"}}, x, y, w, 4)


def series(title, targets, unit, x, y, w=12, h=8, stack=False):
    add({"type": "timeseries", "title": title,
         "targets": [{"datasource": DS, "expr": e, "legendFormat": l, "refId": chr(65 + i)} for i, (e, l) in enumerate(targets)],
         "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": 35 if stack else 10,
                                                               "showPoints": "never",
                                                               "stacking": {"mode": "normal" if stack else "none"}}}},
         "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}}, x, y, w, h)


stat("Orders placed / min", "sum(rate(orders_placed_total[1m])) * 60 or vector(0)", "short", 0, 0)
stat("Time to confirm, p95", "histogram_quantile(0.95, sum by (le) (rate(orders_time_to_confirm_seconds_bucket[5m])))", "s", 6, 0, decimals=1)
stat("Stock rejections / min", "sum(rate(inventory_reservations_total{result=\"rejected\"}[1m])) * 60 or vector(0)", "short", 12, 0)
stat("Emails in dead letter", "sum(notifications_sent_total{result=\"dead_letter\"}) or vector(0)", "short", 18, 0)

series("Orders placed by payment method", [("sum by (method) (rate(orders_placed_total[1m])) * 60", "{{method}}")], "short", 0, 4)
series("Orders finished by outcome", [("sum by (status) (rate(orders_finished_total[1m])) * 60", "{{status}}")], "short", 12, 4, stack=True)
series("Stock reservations", [("sum by (result) (rate(inventory_reservations_total[1m])) * 60", "{{result}}")], "short", 0, 12, w=8, stack=True)
series("Card payments", [("sum by (result) (rate(payments_card_total[1m])) * 60", "{{result}}")], "short", 8, 12, w=8, stack=True)
series("Emails", [("sum by (result) (rate(notifications_sent_total[1m])) * 60", "{{result}}")], "short", 16, 12, w=8, stack=True)
series("Events published from outboxes", [("sum by (type) (rate(outbox_relayed_total[1m]))", "{{type}}")], "reqps", 0, 20)
series("Kafka consumer lag", [("max by (service) (kafka_consumer_fetch_manager_records_lag_max)", "{{service}}")], "short", 12, 20)
series("HTTP p95 by service", [
    ("histogram_quantile(0.95, sum by (le, service) (rate(http_server_requests_seconds_bucket{uri!~\"/actuator.*\"}[1m])))", "{{service}}"),
], "s", 0, 28)
series("JVM heap used", [("sum by (service) (jvm_memory_used_bytes{area=\"heap\"})", "{{service}}")], "bytes", 12, 28)

dashboard = {
    "uid": "surtido",
    "title": "Surtido",
    "tags": ["surtido"],
    "refresh": "5s",
    "time": {"from": "now-30m", "to": "now"},
    "schemaVersion": 39,
    "panels": panels,
}
out = Path(__file__).resolve().parent.parent / "infra" / "grafana" / "dashboards" / "surtido.json"
out.write_text(json.dumps(dashboard, indent=2) + "\n", encoding="utf-8", newline="\n")
print(f"wrote {out.name} with {len(panels)} panels")
