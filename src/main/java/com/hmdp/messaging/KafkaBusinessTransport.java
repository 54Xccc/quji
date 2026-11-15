package com.hmdp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.IBusinessEventService;
import com.hmdp.utils.FailureMessages;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.business-kafka.enabled", havingValue = "true")
public class KafkaBusinessTransport implements EventPublisher {
  private final ObjectMapper json;
  private final IBusinessEventService handler;
  private final AtomicInteger started = new AtomicInteger();
  private final AtomicInteger consuming = new AtomicInteger();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private volatile KafkaProducer<String, String> producer;
  private volatile KafkaConsumer<String, String> consumer;
  private volatile boolean running;

  @Value("${hmdp.business-kafka.bootstrap-servers:127.0.0.1:9092}")
  private String servers;

  @Value("${hmdp.business-kafka.topic:QujiBusiness}")
  private String topic;

  @Value("${hmdp.business-kafka.group:quji-business-v1}")
  private String groupId;

  @Value("${hmdp.business-kafka.consumer-enabled:true}")
  private boolean consumerEnabled;

  public KafkaBusinessTransport(
      ObjectMapper json, IBusinessEventService handler, MeterRegistry metrics) {
    this.json = json;
    this.handler = handler;
    metrics.gauge("hmdp.mq.client.started", started);
    metrics.gauge("hmdp.business.kafka.consumer.running", consuming);
  }

  @EventListener(ContextRefreshedEvent.class)
  public synchronized void start() {
    if (producer != null) {
      return;
    }
    Properties properties = new Properties();
    properties.put("bootstrap.servers", servers);
    properties.put("key.serializer", StringSerializer.class.getName());
    properties.put("value.serializer", StringSerializer.class.getName());
    properties.put("acks", "all");
    properties.put("enable.idempotence", "true");
    properties.put("max.block.ms", "3000");
    properties.put("request.timeout.ms", "3000");
    properties.put("delivery.timeout.ms", "5000");
    producer = new KafkaProducer<>(properties);
    started.set(1);
    if (consumerEnabled) {
      running = true;
      worker.submit(this::consume);
    }
  }

  @Override
  public void publish(BusinessEvent event) {
    KafkaProducer<String, String> active = producer;
    if (active == null || event.getDeliveryId() == null) {
      throw new IllegalStateException("业务 Kafka 未启动或事件缺少投递标识");
    }
    String group = BusinessEventType.from(event.getKind()).getConsumerGroup();
    try {
      String key = group + ":" + event.getAggregateId();
      active.send(new ProducerRecord<>(topic, key, json.writeValueAsString(event)))
          .get(6, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("业务 Kafka 投递被中断", interrupted);
    } catch (Exception failure) {
      throw new IllegalStateException("业务 Kafka 投递未确认", failure);
    }
  }

  private void consume() {
    Properties properties = new Properties();
    properties.put("bootstrap.servers", servers);
    properties.put("group.id", groupId);
    properties.put("key.deserializer", StringDeserializer.class.getName());
    properties.put("value.deserializer", StringDeserializer.class.getName());
    properties.put("enable.auto.commit", "false");
    properties.put("auto.offset.reset", "earliest");
    properties.put("max.poll.records", "20");
    try (KafkaConsumer<String, String> client = new KafkaConsumer<>(properties)) {
      consumer = client;
      client.subscribe(Collections.singleton(topic));
      consuming.set(1);
      while (running) {
        ConsumerRecords<String, String> batch = client.poll(Duration.ofSeconds(1));
        if (!batch.isEmpty()) {
          processBatch(client, batch);
        }
      }
    } catch (WakeupException stopping) {
      if (running) {
        log.warn("业务 Kafka 消费被意外唤醒", stopping);
      }
    } catch (RuntimeException failure) {
      log.error("业务 Kafka 消费者退出", failure);
    } finally {
      consuming.set(0);
      consumer = null;
    }
  }

  private void processBatch(
      KafkaConsumer<String, String> client,
      ConsumerRecords<String, String> batch) {
    try {
      for (ConsumerRecord<String, String> record : batch) {
        process(record);
      }
      // Every record has either completed or left a durable retry/review record.
      client.commitSync();
    } catch (WakeupException stopping) {
      throw stopping;
    } catch (RuntimeException unavailable) {
      Set<TopicPartition> assigned = client.assignment();
      for (TopicPartition partition : batch.partitions()) {
        if (assigned.contains(partition)) {
          client.seek(partition, batch.records(partition).get(0).offset());
        }
      }
      log.warn("业务 Kafka 批次未确认，将重读: {}", FailureMessages.summarize(unavailable));
      try {
        Thread.sleep(500);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        running = false;
      }
    }
  }

  private void process(ConsumerRecord<String, String> record) {
    BusinessEvent event = null;
    String group = "invalid";
    String payload = String.valueOf(record.value());
    try {
      event = json.readValue(payload, BusinessEvent.class);
      if (event == null || event.getDeliveryId() == null) {
        throw new IllegalArgumentException("事件缺少投递标识");
      }
      group = BusinessEventType.from(event.getKind()).getConsumerGroup();
      handler.consume(event, group);
    } catch (Exception failure) {
      RuntimeException reason =
          failure instanceof RuntimeException
              ? (RuntimeException) failure
              : new IllegalArgumentException("消息格式错误", failure);
      String brokerId = record.topic() + ":" + record.partition() + ":" + record.offset();
      if (brokerId.length() > 120) {
        brokerId = UUID.nameUUIDFromBytes(brokerId.getBytes(StandardCharsets.UTF_8)).toString();
      }
      handler.recordConsumptionFailure(event, group, brokerId, payload, reason);
    }
  }

  @PreDestroy
  public synchronized void stop() {
    running = false;
    started.set(0);
    KafkaConsumer<String, String> active = consumer;
    if (active != null) {
      active.wakeup();
    }
    worker.shutdown();
    try {
      worker.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    worker.shutdownNow();
    if (producer != null) {
      producer.close(Duration.ofSeconds(5));
      producer = null;
    }
  }
}
