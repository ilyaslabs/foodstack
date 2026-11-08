# 47 Grow Kafka

[Back to the handbook](README.md)

Kafka carries FoodStack's events between its parts ("order placed", "rider assigned"). Each part writes its events to its own topic, split into 24 **partitions**; each partition is read by one copy of each interested part, in order. Growing Kafka means more **brokers** (machines) and, rarely, more partitions. Kubernetes with a managed or operator-run Kafka 3.7 or newer.

## What you need

- Your Kafka provider's console (Confluent Cloud, Amazon MSK, Aiven) or the Kafka operator (for example Strimzi).
- `my-values.yaml`.
- 20 minutes for brokers. For partitions, a quiet hour.

## Steps

### More brokers

1. Add brokers in the provider's console, or raise the broker count in the operator's resource. Use at least 3, so every event is kept on 3 machines.
2. Spread the existing partitions over the new brokers: managed services do it themselves; with Strimzi, create a `KafkaRebalance`; with plain Kafka, `kafka-reassign-partitions.sh`.
3. FoodStack needs nothing, unless you list brokers by name in `datastores.kafka.bootstrap`. If so, add the new ones and run your usual `helm upgrade`.
4. With 3 or more brokers, keep `datastores.kafka.replicationFactor: 3` (the default). It applies to topics FoodStack creates; existing topics keep theirs, and the provider's console or `kafka-reassign-partitions.sh` raises it.

### More partitions (with care)

24 partitions let up to 24 copies of each part read events at once, plenty for one country. Add more only when a part has 24 copies and still lags ([guide 30](30-when-to-grow.md)).

Events about the same order always go to the same partition, so they are handled in order. Adding partitions moves some orders to another partition, and for a minute their events could be handled out of order. So:

5. Choose a quiet hour (for example 4 in the morning) and a new count that is a multiple of the old (48).
6. In `my-values.yaml`:
   ```yaml
   datastores:
     kafka:
       topicPartitions: 48
   ```
7. Apply with `helm upgrade` (as usual). On start, FoodStack adds the partitions to each of its topics, and the chart raises the number of listener threads it allows.
8. **Partitions cannot be removed.** Going back to 24 needs new topics; do not lower the setting.

## Check it worked

- Brokers: the provider's console shows every broker online and partitions spread over them. No "under-replicated partitions".
- Partitions: `kafka-topics.sh --describe --topic foodstack.order.events` (or the console) shows `PartitionCount: 48`. The **FoodStack events** dashboard shows the lag going back to zero at peak.
- A test order goes through, with the restaurant and the customer told at each step.

## Undo

- Brokers: remove them in the console, which first moves their partitions away.
- Partitions: none (step 8).

## If something goes wrong

| Problem | What to do |
|---|---|
| Server pods log "Topic … not present in metadata" or cannot connect | `datastores.kafka.bootstrap` must list brokers the pods can reach, with the right port and TLS settings |
| "NOT_ENOUGH_REPLICAS" when FoodStack writes | Fewer brokers are up than the replication factor needs. Bring brokers back, or lower `replicationFactor` until they are |
| Events lag after adding partitions | The parts with the lag need more copies to use them ([runbook 41](41-add-server-replicas.md)) |
