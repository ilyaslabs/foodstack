#!/usr/bin/env bash
# Kafka: one KRaft broker, advertised on its fixed address so pods in the cluster reach it.
source "$(dirname "$0")/../lib.sh"
network
run_container fsplay-kafka "$KAFKA_IP" \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://$KAFKA_IP:9092" \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_HEAP_OPTS="-Xmx512m -Xms512m" \
  "$KAFKA_IMAGE"
wait_for "fsplay-kafka" 90 docker exec fsplay-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
info "Kafka on $KAFKA_IP:9092"
