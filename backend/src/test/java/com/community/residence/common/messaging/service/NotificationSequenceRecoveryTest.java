package com.community.residence.common.messaging.service;

import com.community.residence.common.messaging.entity.Notification;
import com.community.residence.common.messaging.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证 Redis 故障、恢复及进程重启后增量通知游标的连续性。 */
class NotificationSequenceRecoveryTest {
    private final NotificationMapper mapper = mock(NotificationMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final WebSocketSessionService sessions = mock(WebSocketSessionService.class);
    private final NotificationService service = new NotificationService(
            new AfterCommitDeliveryService(), mapper, null, redis, null, sessions, null);

    private void emit() {
        service.createForResident(1L, 1L, "恢复测试", "通知正文", "SYSTEM", null, null);
    }

    private List<Long> sequences(int count) {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mapper, times(count)).insert(captor.capture());
        return captor.getAllValues().stream().map(Notification::getSeq).toList();
    }

    @Test
    void outageThenRecoveryNeverMovesCursorBackwards() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(values);
        lenient().when(values.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("offline")).thenReturn(2L);
        AtomicLong redisCounter = new AtomicLong(1L);
        lenient().when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("offline"))
                .thenAnswer(call -> redisCounter.updateAndGet(current ->
                        Math.max(current, Long.parseLong(call.getArgument(2))) + 1));
        emit();
        emit();
        assertThat(sequences(2).get(1)).isGreaterThan(sequences(2).get(0));
    }

    @Test
    void restartRestoresDatabaseCursorBeforeUsingFreshRedis() {
        long persisted = System.currentTimeMillis() + 100_000;
        when(mapper.selectMaxSeq()).thenReturn(persisted);
        lenient().when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenAnswer(call -> Long.parseLong(call.getArgument(2)) + 1);
        emit();
        assertThat(sequences(1).get(0)).isGreaterThan(persisted);
    }

    @Test
    void rapidOutageNotificationsRemainUniqueAndIncreasing() {
        lenient().when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));
        lenient().when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("offline"));
        for (int i = 0; i < 30; i++) emit();
        List<Long> seq = sequences(30);
        assertThat(seq).doesNotHaveDuplicates();
        for (int i = 1; i < seq.size(); i++) assertThat(seq.get(i)).isGreaterThan(seq.get(i - 1));
    }

    @Test
    void commandErrorsAreNeverConvertedIntoSuccessfulNotifications() {
        lenient().when(redis.opsForValue()).thenThrow(new InvalidDataAccessApiUsageException("WRONGTYPE"));
        lenient().when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new InvalidDataAccessApiUsageException("WRONGTYPE"));
        assertThatThrownBy(this::emit).isInstanceOf(InvalidDataAccessApiUsageException.class);
        verify(mapper, never()).insert(any(Notification.class));
    }
}
