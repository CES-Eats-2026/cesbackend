package com.ceseats.config.redis;

import com.ceseats.config.redis.util.RedisOperator;
import com.ceseats.service.RagAsyncStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamConsumer implements StreamListener<String, MapRecord<String, Object, Object>>, InitializingBean, DisposableBean {
    private StreamMessageListenerContainer<String, MapRecord<String, Object, Object>> listenerContainer;
    private final List<Subscription> llmSubscriptions = new ArrayList<>();
    private final List<Subscription> dbSubscriptions = new ArrayList<>();
    private ExecutorService executorService;

    private String llmStreamKey;
    private String llmConsumerGroupName;

    private String dbStreamKey;
    private String dbConsumerGroupName;

    private final RedisOperator redisOperator;
    private final RagAsyncStreamService ragAsyncStreamService;

    @Value("${rag.stream.llm.consumers:2}")
    private int llmConsumerCount;

    @Value("${rag.stream.db.consumers:2}")
    private int dbConsumerCount;

    @Value("${rag.stream.delete-after-ack:true}")
    private boolean deleteAfterAck;

    @Override
    public void onMessage(MapRecord<String, Object, Object> message) {
        final long t0 = System.nanoTime();
        String stream = message.getStream();
        Map<Object, Object> body = message.getValue();
        String requestId = body != null && body.get("requestId") != null ? body.get("requestId").toString() : null;
        String payload = body != null && body.get("payload") != null ? body.get("payload").toString() : null;
        if (requestId != null) requestId = requestId.trim();
        // stream hashValue가 JSON 문자열로 들어올 수 있어 정규화
        requestId = ragAsyncStreamService.normalizeRequestId(requestId);
        Long createdAtMs = null;
        try {
            if (body != null && body.get("createdAt") != null) {
                String raw = body.get("createdAt").toString().trim();
                raw = ragAsyncStreamService.normalizeRequestId(raw); // "\"123\"" -> "123" 처리에 재사용
                createdAtMs = Long.parseLong(raw);
            }
        } catch (Exception ignore) {
        }
        long queueDelayMs = createdAtMs != null ? (System.currentTimeMillis() - createdAtMs) : -1L;

        if (requestId == null || payload == null) {
            log.warn("[RedisStreamConsumer] invalid message. stream={}, id={}, body={}", stream, message.getId(), body);
            // ack는 가능한 경우에만 처리
            if (RagAsyncStreamService.STREAM_LLM.equals(stream)) {
                this.redisOperator.ackStream(llmConsumerGroupName, message);
            } else if (RagAsyncStreamService.STREAM_DB.equals(stream)) {
                this.redisOperator.ackStream(dbConsumerGroupName, message);
            }
            return;
        }

        try {
            if (RagAsyncStreamService.STREAM_LLM.equals(stream)) {
                log.info("[RAG][{}] consume llm_requests: queueDelayMs={}, msgId={}", requestId, queueDelayMs, message.getId());
                ragAsyncStreamService.handleLlmMessage(requestId, payload);
                final long tAckStart = System.nanoTime();
                this.redisOperator.ackStream(llmConsumerGroupName, message);
                if (deleteAfterAck) {
                    try {
                        this.redisOperator.deleteStreamEntry(llmStreamKey, message.getId());
                    } catch (Exception e) {
                        log.warn("[RAG][{}] xdel llm_requests failed msgId={}: {}", requestId, message.getId(), e.getMessage());
                    }
                }
                log.info("[RAG][{}] ack llm_requests: ackMs={}, totalConsumerMs={}",
                        requestId, (System.nanoTime() - tAckStart) / 1_000_000L, (System.nanoTime() - t0) / 1_000_000L);
            } else if (RagAsyncStreamService.STREAM_DB.equals(stream)) {
                log.info("[RAG][{}] consume db_requests: queueDelayMs={}, msgId={}", requestId, queueDelayMs, message.getId());
                ragAsyncStreamService.handleDbMessage(requestId, payload);
                final long tAckStart = System.nanoTime();
                this.redisOperator.ackStream(dbConsumerGroupName, message);
                if (deleteAfterAck) {
                    try {
                        this.redisOperator.deleteStreamEntry(dbStreamKey, message.getId());
                    } catch (Exception e) {
                        log.warn("[RAG][{}] xdel db_requests failed msgId={}: {}", requestId, message.getId(), e.getMessage());
                    }
                }
                log.info("[RAG][{}] ack db_requests: ackMs={}, totalConsumerMs={}",
                        requestId, (System.nanoTime() - tAckStart) / 1_000_000L, (System.nanoTime() - t0) / 1_000_000L);
            } else {
                log.debug("[RedisStreamConsumer] ignore stream={}", stream);
            }
        } catch (Exception e) {
            log.error("[RedisStreamConsumer] onMessage failed stream={}, requestId={}", stream, requestId, e);
            // 예외 발생 시에도 메시지가 쌓이지 않도록 ack (재처리 로직은 추후 확장)
            if (RagAsyncStreamService.STREAM_LLM.equals(stream)) {
                this.redisOperator.ackStream(llmConsumerGroupName, message);
            } else if (RagAsyncStreamService.STREAM_DB.equals(stream)) {
                this.redisOperator.ackStream(dbConsumerGroupName, message);
            }
        }
    }

    @Override
    public void destroy() throws Exception {
        for (Subscription s : llmSubscriptions) {
            try { if (s != null) s.cancel(); } catch (Exception ignore) {}
        }
        for (Subscription s : dbSubscriptions) {
            try { if (s != null) s.cancel(); } catch (Exception ignore) {}
        }
        if(this.listenerContainer != null){
            this.listenerContainer .stop();
        }
        if (this.executorService != null) {
            this.executorService.shutdownNow();
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // ver.2: RagRecommendationController가 enqueue한 요청만 처리
        this.llmStreamKey = RagAsyncStreamService.STREAM_LLM;
        this.llmConsumerGroupName = RagAsyncStreamService.GROUP_LLM;

        this.dbStreamKey = RagAsyncStreamService.STREAM_DB;
        this.dbConsumerGroupName = RagAsyncStreamService.GROUP_DB;

        // Consumer Group 설정 (stream 없으면 MKSTREAM 포함)
        this.redisOperator.createStreamConsumerGroup(llmStreamKey, llmConsumerGroupName);
        this.redisOperator.createStreamConsumerGroup(dbStreamKey, dbConsumerGroupName);

        // 컨슈머 개수 보정 (최소 1)
        int llmN = Math.max(1, llmConsumerCount);
        int dbN = Math.max(1, dbConsumerCount);
        int totalN = llmN + dbN;

        // 인스턴스 고유 ID (pid@hostname) 기반으로 consumerName 유니크하게
        String instanceId = ManagementFactory.getRuntimeMXBean().getName(); // ex) "12345@host"
        instanceId = instanceId.replaceAll("[^a-zA-Z0-9@._-]", "_");

        // StreamMessageListenerContainer 설정 (executor로 병렬 처리)
        this.executorService = Executors.newFixedThreadPool(totalN);
        this.listenerContainer = this.redisOperator.createStreamMessageListenerContainer(this.executorService);

        // llm_group consumers 구독 생성
        for (int i = 1; i <= llmN; i++) {
            String consumerName = "llm-worker-" + instanceId + "-" + i;
            Subscription sub = this.listenerContainer.receive(
                    Consumer.from(this.llmConsumerGroupName, consumerName),
                    StreamOffset.create(llmStreamKey, ReadOffset.lastConsumed()),
                    this
            );
            llmSubscriptions.add(sub);
        }

        // db_group consumers 구독 생성
        for (int i = 1; i <= dbN; i++) {
            String consumerName = "db-worker-" + instanceId + "-" + i;
            Subscription sub = this.listenerContainer.receive(
                    Consumer.from(this.dbConsumerGroupName, consumerName),
                    StreamOffset.create(dbStreamKey, ReadOffset.lastConsumed()),
                    this
            );
            dbSubscriptions.add(sub);
        }

        // redis listen 시작
        this.listenerContainer.start();

        // 초기 구독이 붙을 시간 확보 (필수는 아니지만 로그/안정성용)
        for (Subscription s : llmSubscriptions) {
            if (s != null) s.await(Duration.ofSeconds(1));
        }
        for (Subscription s : dbSubscriptions) {
            if (s != null) s.await(Duration.ofSeconds(1));
        }

        log.info("[RedisStreamConsumer] started. llmConsumers={}, dbConsumers={}, instanceId={}", llmN, dbN, instanceId);
    }
}
