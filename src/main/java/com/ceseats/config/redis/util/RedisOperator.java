package com.ceseats.config.redis.util;

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

@Component
@RequiredArgsConstructor
public class RedisOperator {
    private final RedisTemplate<String, Object> redisTemplate;

    public Object getRedisValue(String key, String field){
        return this.redisTemplate.opsForHash().get(key, field);
    }

    public long increaseRedisValue(String key, String field){
        return this.redisTemplate.opsForHash().increment(key, field, 1);
    }

    public void ackStream(String consumerGroupName, MapRecord<String, Object, Object> message){
        this.redisTemplate.opsForStream().acknowledge(consumerGroupName, message);
    }

    /** Stream 엔트리 삭제 (XDEL) */
    public Long deleteStreamEntry(String streamKey, RecordId recordId) {
        if (streamKey == null || recordId == null) return 0L;
        return this.redisTemplate.opsForStream().delete(streamKey, recordId.getValue());
    }

    /** 단순 String 값을 TTL과 함께 저장 */
    public void setStringValue(String key, String value, Duration ttl) {
        if (ttl != null) {
            this.redisTemplate.opsForValue().set(key, value, ttl);
        } else {
            this.redisTemplate.opsForValue().set(key, value);
        }
    }

    public String getStringValue(String key) {
        Object v = this.redisTemplate.opsForValue().get(key);
        return v != null ? v.toString() : null;
    }

    /** Redis Streams에 메시지 추가 (XADD) */
    public String addToStream(String streamKey, Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }
        RecordId id = this.redisTemplate.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(streamKey));
        return id != null ? id.getValue() : null;
    }

    public void claimStream(PendingMessage pendingMessage, String consumerName){
        RedisAsyncCommands commands = (RedisAsyncCommands) this.redisTemplate
                .getConnectionFactory().getConnection().getNativeConnection();

        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(pendingMessage.getIdAsString())
                .add(pendingMessage.getGroupName())
                .add(consumerName)
                .add("20")
                .add(pendingMessage.getIdAsString());
        commands.dispatch(CommandType.XCLAIM, new StatusOutput(StringCodec.UTF8), args);
    }

    public MapRecord<String, Object, Object> findStreamMessageById(String streamKey, String id){
        List<MapRecord<String, Object, Object>> mapRecordList = this.findStreamMessageByRange(streamKey, id, id);
        if(mapRecordList.isEmpty()) return null;
        return mapRecordList.get(0);
    }

    public List<MapRecord<String, Object, Object>> findStreamMessageByRange(String streamKey, String startId, String endId){
        return this.redisTemplate.opsForStream().range(streamKey, Range.closed(startId, endId));
    }

    public void createStreamConsumerGroup(String streamKey, String consumerGroupName){
        //if stream is not exist, create stream and consumer group of it
        if (Boolean.FALSE.equals(this.redisTemplate.hasKey(streamKey))){
            RedisAsyncCommands commands = (RedisAsyncCommands) this.redisTemplate
                    .getConnectionFactory()
                    .getConnection()
                    .getNativeConnection();

            CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                    .add(CommandKeyword.CREATE)
                    .add(streamKey)
                    .add(consumerGroupName)
                    .add("0")
                    .add("MKSTREAM");

            commands.dispatch(CommandType.XGROUP, new StatusOutput(StringCodec.UTF8), args);
        }
        //stream is exist, create consumerGroup if is not exist
        else{
            if(!isStreamConsumerGroupExist(streamKey, consumerGroupName)){
                this.redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), consumerGroupName);
            }
        }
    }

    public PendingMessages findStreamPendingMessages(String streamKey, String consumerGroupName, String consumerName){
        return this.redisTemplate.opsForStream()
                .pending(streamKey, Consumer.from(consumerGroupName, consumerName), Range.unbounded(), 100L);
    }

    public boolean isStreamConsumerGroupExist(String streamKey, String consumerGroupName){
        Iterator<StreamInfo.XInfoGroup> iterator = this.redisTemplate
                .opsForStream().groups(streamKey).stream().iterator();

        while(iterator.hasNext()){
            StreamInfo.XInfoGroup xInfoGroup = iterator.next();
            if(xInfoGroup.groupName().equals(consumerGroupName)){
                return true;
            }
        }
        return false;
    }

    public StreamMessageListenerContainer createStreamMessageListenerContainer(){
        return StreamMessageListenerContainer.create(
                this.redisTemplate.getConnectionFactory(),
                StreamMessageListenerContainer
                        .StreamMessageListenerContainerOptions.builder()
                        .hashKeySerializer(new StringRedisSerializer())
                        .hashValueSerializer(new StringRedisSerializer())
                        .pollTimeout(Duration.ofMillis(20))
                        .build()
        );
    }

    public StreamMessageListenerContainer createStreamMessageListenerContainer(Executor executor){
        return StreamMessageListenerContainer.create(
                this.redisTemplate.getConnectionFactory(),
                StreamMessageListenerContainer
                        .StreamMessageListenerContainerOptions.builder()
                        .hashKeySerializer(new StringRedisSerializer())
                        .hashValueSerializer(new StringRedisSerializer())
                        .pollTimeout(Duration.ofMillis(20))
                        .executor(executor)
                        .build()
        );
    }

}