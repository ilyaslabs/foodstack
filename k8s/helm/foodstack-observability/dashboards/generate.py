"""Writes the FoodStack Grafana dashboards: python3 generate.py . (run in this folder)."""
import json
import sys

OUT = sys.argv[1]
DS = {"type": "prometheus", "uid": "prometheus"}
SEL = 'job="foodstack-server", namespace=~"$namespace"'


def variables():
    return {"list": [
        {"name": "namespace", "label": "Namespace", "type": "query", "datasource": DS,
         "query": {"query": 'label_values(up{job="foodstack-server"}, namespace)', "refId": "ns"},
         "definition": 'label_values(up{job="foodstack-server"}, namespace)',
         "refresh": 2, "includeAll": True, "multi": False, "current": {"text": "All", "value": "$__all"}},
    ]}


class Board:
    def __init__(self, uid, title, description):
        self.uid, self.title, self.description = uid, title, description
        self.panels, self.y, self.id = [], 0, 1

    def row(self, title):
        self.panels.append({"type": "row", "title": title, "id": self.id, "collapsed": False,
                            "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}})
        self.id += 1
        self.y += 1
        self.x = 0

    def _add(self, panel, w, h):
        if getattr(self, "x", 0) + w > 24:
            self.x = 0
            self.y += self.last_h
        panel["id"] = self.id
        panel["gridPos"] = {"h": h, "w": w, "x": self.x, "y": self.y}
        panel["datasource"] = DS
        self.id += 1
        self.x += w
        self.last_h = h
        if self.x >= 24:
            self.x = 0
            self.y += h
        self.panels.append(panel)

    def series(self, title, targets, unit="short", w=12, h=8, description="", stack=False, minimum=0):
        self._add({
            "type": "timeseries", "title": title, "description": description,
            "fieldConfig": {"defaults": {"unit": unit, "min": minimum, "custom": {
                "lineWidth": 2, "fillOpacity": 10, "showPoints": "never",
                "stacking": {"mode": "normal" if stack else "none"}}}, "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom"},
                        "tooltip": {"mode": "multi", "sort": "desc"}},
            "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": l, "datasource": DS}
                        for i, (e, l) in enumerate(targets)],
        }, w, h)

    def stat(self, title, expr, unit="short", w=6, h=4, thresholds=None, description=""):
        steps = [{"color": "green", "value": None}] + [
            {"color": c, "value": v} for v, c in (thresholds or [])]
        self._add({
            "type": "stat", "title": title, "description": description,
            "fieldConfig": {"defaults": {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps},
                                         "color": {"mode": "thresholds"}}, "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background",
                        "graphMode": "area", "textMode": "value"},
            "targets": [{"refId": "A", "expr": expr, "datasource": DS}],
        }, w, h)

    def write(self):
        board = {
            "uid": self.uid, "title": self.title, "description": self.description,
            "tags": ["foodstack"], "timezone": "browser", "schemaVersion": 39, "version": 1,
            "editable": False, "graphTooltip": 1, "refresh": "30s",
            "time": {"from": "now-6h", "to": "now"}, "templating": variables(),
            "annotations": {"list": []}, "panels": self.panels,
            "links": [{"title": "FoodStack", "type": "dashboards", "tags": ["foodstack"], "asDropdown": True}],
        }
        with open(f"{OUT}/{self.uid.removeprefix('foodstack-')}.json", "w") as f:
            json.dump(board, f, indent=2, ensure_ascii=False)
            f.write("\n")


API = f'{SEL}, uri=~"/api/.*"'

# Overview: golden signals per module group
b = Board("foodstack-overview", "FoodStack overview",
          "Traffic, errors, latency and resources of every FoodStack pod group.")
b.row("Now")
b.stat("Requests per second", f"sum(rate(http_server_requests_seconds_count{{{API}}}[5m]))", "reqps")
b.stat("Server errors", f"sum(rate(http_server_requests_seconds_count{{{API}, status=~\"5..\"}}[5m])) / "
       f"sum(rate(http_server_requests_seconds_count{{{API}}}[5m]))", "percentunit",
       thresholds=[(0.01, "orange"), (0.02, "red")])
b.stat("95 % of requests within", "histogram_quantile(0.95, sum by (le) "
       f"(rate(http_server_requests_seconds_bucket{{{API}}}[5m])))", "s",
       thresholds=[(0.5, "orange"), (1, "red")])
b.stat("Pods up", f"sum(up{{{SEL}}})", thresholds=None,
       description="FoodStack server pods Prometheus can read.")
b.row("Traffic by pod group")
b.series("Requests per second", [(
    f"sum by (component) (rate(http_server_requests_seconds_count{{{API}}}[5m]))", "{{component}}")],
    "reqps", stack=True)
b.series("Server errors per second", [(
    f"sum by (component) (rate(http_server_requests_seconds_count{{{API}, status=~\"5..\"}}[5m]))",
    "{{component}}")], "reqps")
b.series("Latency, 95th percentile", [(
    "histogram_quantile(0.95, sum by (component, le) "
    f"(rate(http_server_requests_seconds_bucket{{{API}}}[5m])))", "{{component}}")], "s")
b.series("Slowest endpoints, 95th percentile", [(
    "topk(8, histogram_quantile(0.95, sum by (method, uri, le) "
    f"(rate(http_server_requests_seconds_bucket{{{API}}}[5m]))))", "{{method}} {{uri}}")], "s")
b.series("Client errors per second (4xx)", [(
    f"sum by (status) (rate(http_server_requests_seconds_count{{{API}, status=~\"4..\"}}[5m]))",
    "{{status}}")], "reqps", description="Many 401 or 429 answers can mean an attack or a broken client.")
b.series("Calls between modules", [(
    f"sum by (module, mode) (rate(module_switch_call_seconds_count{{{SEL}}}[5m]))", "{{module}} ({{mode}})")],
    "ops", description="Local calls stay in the pod; remote ones cross the network.")
b.row("Resources")
b.series("Heap used after garbage collection", [(
    f"max by (pod) (jvm_memory_usage_after_gc{{{SEL}, area=\"heap\"}})", "{{pod}}")], "percentunit")
b.series("CPU", [(f"max by (pod) (process_cpu_usage{{{SEL}}})", "{{pod}}")], "percentunit")
b.series("Database time per call", [(
    f"sum by (component) (rate(mongodb_driver_commands_seconds_sum{{{SEL}}}[5m])) / "
    f"sum by (component) (rate(mongodb_driver_commands_seconds_count{{{SEL}}}[5m]))", "{{component}}")], "s")
b.series("Redis time per call", [(
    f"sum by (component) (rate(lettuce_seconds_sum{{{SEL}}}[5m])) / "
    f"sum by (component) (rate(lettuce_seconds_count{{{SEL}}}[5m]))", "{{component}}")], "s")
b.write()

# Orders and delivery
b = Board("foodstack-orders", "FoodStack orders and delivery",
          "The order funnel and how fast riders are found and orders delivered.")
b.row("Last 24 hours")
b.stat("Orders placed", f"sum(increase(foodstack_orders_total{{{SEL}, status=\"PLACED\"}}[24h]))")
b.stat("Completed", f"sum(increase(foodstack_orders_total{{{SEL}, status=~\"DELIVERED|COLLECTED\"}}[24h]))",
       description="Delivered or collected by the customer.")
b.stat("Cancelled or rejected", f"sum(increase(foodstack_orders_total{{{SEL}, status=~\"CANCELLED|REJECTED\"}}[24h])) / "
       f"sum(increase(foodstack_orders_total{{{SEL}, status=\"PLACED\"}}[24h]))", "percentunit",
       thresholds=[(0.05, "orange"), (0.1, "red")])
b.stat("Rider found within (95 %)", "histogram_quantile(0.95, sum by (le) "
       f"(rate(foodstack_dispatch_assign_time_seconds_bucket{{{SEL}}}[24h])))", "s",
       thresholds=[(300, "orange"), (600, "red")])
b.row("Orders")
b.series("Orders by status, per hour", [(
    f"sum by (status) (increase(foodstack_orders_total{{{SEL}}}[1h]))", "{{status}}")], w=24,
    description="Each order counts once in every status it reaches.")
b.row("Delivery")
b.series("Delivery jobs by status, per hour", [(
    f"sum by (status) (increase(foodstack_dispatch_jobs_total{{{SEL}}}[1h]))", "{{status}}")])
b.series("Time to find a rider", [
    ("histogram_quantile(0.5, sum by (le) "
     f"(rate(foodstack_dispatch_assign_time_seconds_bucket{{{SEL}}}[30m])))", "median"),
    ("histogram_quantile(0.95, sum by (le) "
     f"(rate(foodstack_dispatch_assign_time_seconds_bucket{{{SEL}}}[30m])))", "95 %")], "s")
b.series("Pickup to delivery", [
    ("histogram_quantile(0.5, sum by (le) "
     f"(rate(foodstack_dispatch_delivery_time_seconds_bucket{{{SEL}}}[30m])))", "median"),
    ("histogram_quantile(0.95, sum by (le) "
     f"(rate(foodstack_dispatch_delivery_time_seconds_bucket{{{SEL}}}[30m])))", "95 %")], "s")
b.write()

# Events: outbox, Kafka, dead letters
b = Board("foodstack-events", "FoodStack events",
          "Events between modules: the outbox, Kafka consumers and failed events.")
b.row("Now")
b.stat("Events waiting for Kafka", f"sum(max by (module) (foodstack_outbox_pending{{{SEL}}}))",
       thresholds=[(1000, "orange"), (10000, "red")])
b.stat("Oldest waiting event", f"max(foodstack_outbox_oldest_age_seconds{{{SEL}}})", "s",
       thresholds=[(30, "orange"), (120, "red")])
b.stat("Largest consumer lag", f"max(kafka_consumer_fetch_manager_records_lag_max{{{SEL}}})",
       thresholds=[(1000, "orange"), (10000, "red")])
b.stat("Failed events, 24 h", f"sum(increase(foodstack_dead_letters_total{{{SEL}}}[24h]))",
       thresholds=[(1, "red")], description="Replay or dismiss them in the back office: Settings, Failed events.")
b.row("Outbox")
b.series("Published per second", [(
    f"sum by (module) (rate(foodstack_outbox_published_total{{{SEL}}}[5m]))", "{{module}}")], "ops", stack=True)
b.series("Oldest waiting event", [(
    f"max by (module) (foodstack_outbox_oldest_age_seconds{{{SEL}}})", "{{module}}")], "s")
b.row("Consumers")
b.series("Handled per second", [(
    f"sum by (messaging_kafka_consumer_group) (rate(spring_kafka_listener_seconds_count{{{SEL}}}[5m]))",
    "{{messaging_kafka_consumer_group}}")], "ops")
b.series("Consumer lag", [(
    f"max by (group) (label_replace(kafka_consumer_fetch_manager_records_lag_max{{{SEL}}}, \"group\", \"$1\", \"client_id\", \"consumer-(.+)-[0-9]+\"))",
    "{{group}}")])
b.series("Handler failures per second", [(
    f"sum by (messaging_kafka_consumer_group, messaging_source_name) (rate(spring_kafka_listener_seconds_count{{{SEL}, error!=\"none\"}}[5m]))",
    "{{messaging_kafka_consumer_group}} on {{messaging_source_name}}")],
    "ops", description="Failures are retried; after the last retry the event becomes a failed event.")
b.series("Failed events", [(
    f"sum by (namespace) (increase(foodstack_dead_letters_total{{{SEL}}}[1h]))", "per hour")])
b.write()
