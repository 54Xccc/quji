package com.hmdp.service.impl;

import com.hmdp.entity.BusinessOutboxTask;
import com.hmdp.enums.OutboxState;
import com.hmdp.mapper.BusinessOutboxMapper;
import com.hmdp.mapper.MessageMapper;
import com.hmdp.mapper.SeckillReservationMapper;
import com.hmdp.messaging.BusinessEvent;
import com.hmdp.messaging.BusinessEventType;
import com.hmdp.messaging.DeliveryRetryPolicy;
import com.hmdp.messaging.ProjectionResult;
import com.hmdp.service.IBusinessEventService;
import com.hmdp.service.IBusinessProjectionService;
import com.hmdp.utils.FailureMessages;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BusinessEventServiceImpl implements IBusinessEventService {

  @Resource
  private BusinessOutboxMapper businessOutboxMapper;

  @Resource
  private MessageMapper messageMapper;

  @Resource
  private SeckillReservationMapper seckillReservationMapper;

  private final TransactionTemplate transactions;
  private final IBusinessProjectionService projector;
  private final MeterRegistry metrics;

  public BusinessEventServiceImpl(
      TransactionTemplate transactions,
      IBusinessProjectionService projector,
      MeterRegistry metrics) {
    this.transactions = new TransactionTemplate(transactions.getTransactionManager());
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.projector = projector;
    this.metrics = metrics;
  }

  public static String resolveConsumerGroup(String kind) {
    return BusinessEventType.from(kind).getConsumerGroup();
  }

  @Override
  public void consume(BusinessEvent event, String group) {
    if (event.getSchemaVersion() != 1
        || event.getEventId() <= 0
        || event.getKind() == null
        || !group.equals(resolveConsumerGroup(event.getKind()))) {
      throw new IllegalArgumentException("事件版本、事件号或消费组无效");
    }
    long started = System.nanoTime();
    try {
      transactions.execute(
          tx -> {
            consumeLockedTask(event, group);
            return null;
          });
    } finally {
      metrics
          .timer("hmdp.mq.consume", "group", group)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  private void consumeLockedTask(BusinessEvent event, String group) {
    // 只锁当前事件；业务效果、Inbox 和任务完成状态仍在同一事务提交。
    BusinessOutboxTask task = businessOutboxMapper.selectEventForUpdate(event.getEventId());
    if (task == null) {
      throw new IllegalArgumentException("消息没有对应的持久化事件");
    }
    if (!event.getKind().equals(task.getKind())
        || event.getAggregateId() != task.getAggregateId()) {
      throw new IllegalArgumentException("消息内容与持久化事件不一致");
    }
    if (event.getDeliveryId() != null && !event.getDeliveryId().equals(task.getDeliveryId())) {
      return;
    }
    if (messageMapper.countInboxEvent(group, event.getEventId()) > 0) {
      businessOutboxMapper.markDuplicateDone(event.getEventId());
      metrics.counter("hmdp.mq.consumptions", "group", group, "result", "duplicate").increment();
      return;
    }
    if (OutboxState.REVIEW.matches(task.getState())) {
      throw new IllegalStateException("事件等待人工复核");
    }
    if (projector.project(task) == ProjectionResult.CONTINUE) {
      appendFeedContinuation(task);
    }
    messageMapper.insertInboxEvent(group, event.getEventId());
    businessOutboxMapper.markConsumed(event.getEventId());
    metrics.counter("hmdp.mq.consumptions", "group", group, "result", "success").increment();
  }

  private void appendFeedContinuation(BusinessOutboxTask task) {
    // 游标由当前批次推进；后续批次独立持久化，沿用原发布时间。
    long cursor = businessOutboxMapper.selectCursor(task.getId());
    businessOutboxMapper.insertFeedContinuation(
        task.getKind(), task.getAggregateId(), task.getUserId(), cursor, task.getCreatedAt());
  }

  /** 将重试或复核状态持久化后，消费者才可提交 Kafka offset。 */
  @Override
  public void recordConsumptionFailure(
      BusinessEvent event,
      String group,
      String brokerId,
      String payload,
      RuntimeException failure) {
    String reason = FailureMessages.summarize(failure);
    transactions.execute(
        tx -> {
          BusinessOutboxTask task = matchedTask(event, group);
          if (task == null) {
            messageMapper.insertFailure(
                group, "broker:" + brokerId, null, brokerId, payload, reason);
            metrics
                .counter("hmdp.mq.consumptions", "group", group, "result", "quarantined")
                .increment();
            return null;
          }
          // A redelivery of the same record cannot spend another retry or undo a newer result.
          if (!event.getDeliveryId().equals(task.getDeliveryId())
              || OutboxState.DONE.matches(task.getState())
              || OutboxState.REVIEW.matches(task.getState())) {
            return null;
          }
          int attempts = task.getConsumeAttempts() + 1;
          if (attempts < DeliveryRetryPolicy.MAX_CONSUME_ATTEMPTS) {
            businessOutboxMapper.scheduleConsumeRetry(
                reason, DeliveryRetryPolicy.delaySeconds(attempts), task.getId());
            metrics.counter("hmdp.mq.consumptions", "group", group, "result", "retry").increment();
            return null;
          }
          moveToReview(task, group, brokerId, payload, reason);
          return null;
        });
  }

  private BusinessOutboxTask matchedTask(BusinessEvent event, String group) {
    if (event == null
        || event.getEventId() <= 0
        || event.getSchemaVersion() != 1
        || event.getDeliveryId() == null
        || "invalid".equals(group)) {
      return null;
    }
    BusinessOutboxTask task = businessOutboxMapper.selectEventForUpdate(event.getEventId());
    if (task == null
        || !Objects.equals(event.getKind(), task.getKind())
        || event.getAggregateId() != task.getAggregateId()
        || !group.equals(resolveConsumerGroup(task.getKind()))) {
      return null;
    }
    return task;
  }

  private void moveToReview(
      BusinessOutboxTask task, String group, String brokerId, String payload, String reason) {
    long id = task.getId();
    businessOutboxMapper.recordConsumeFailure(reason, id);
    messageMapper.insertFailure(group, "outbox:" + id, id, brokerId, payload, reason);
    businessOutboxMapper.markReview(id);
    if ("SECKILL_FULFILL".equals(task.getKind())
        && seckillReservationMapper.markProcessingForReview(reason, task.getAggregateId()) > 0) {
      seckillReservationMapper.insertDeadLetter(
          "mq:outbox:" + id, task.getAggregateId(), payload, reason);
    }
    metrics.counter("hmdp.mq.consumptions", "group", group, "result", "quarantined").increment();
  }
}
