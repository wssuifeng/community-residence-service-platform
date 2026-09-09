package com.community.residence.schedule.task;

import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.service.HousingService;
import com.community.residence.schedule.service.TaskLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 浏览统计回写任务测试：计数回写、键删除、异常还原、无效键丢弃 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HousingViewFlushTask 单元测试")
class HousingViewFlushTaskTest {

    @Mock
    private HousingMapper housingMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private TaskLogService taskLogService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private Cursor<String> cursor;

    @InjectMocks
    private HousingViewFlushTask task;

    private void mockLockAndScan(List<String> keys) throws InterruptedException {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock(anyLong(), any())).thenReturn(true);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        var iterator = keys.iterator();
        lenient().when(cursor.hasNext()).thenAnswer(inv -> iterator.hasNext());
        lenient().when(cursor.next()).thenAnswer(inv -> iterator.next());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("回写：计数原子取出并累加到 view_count")
    void execute_flushesCounterToDatabase() throws Exception {
        mockLockAndScan(List.of("housing:view:5"));
        when(valueOps.getAndDelete("housing:view:5")).thenReturn("3");
        when(housingMapper.update(isNull(), any())).thenReturn(1);

        task.execute();

        verify(housingMapper).update(isNull(), argThat(wrapper -> true));
        verify(taskLogService).record(anyString(), any(), anyLong(), eq(1), eq(1), eq(0), isNull());
    }

    @Test
    @DisplayName("回写失败：计数还原回 Redis 待重试")
    void execute_flushFailure_restoresCounter() throws Exception {
        mockLockAndScan(List.of("housing:view:5"));
        when(valueOps.getAndDelete("housing:view:5")).thenReturn("3");
        when(housingMapper.update(isNull(), any())).thenThrow(new RuntimeException("db down"));

        task.execute();

        verify(valueOps).increment("housing:view:5", 3);
        verify(taskLogService).record(anyString(), any(), anyLong(), eq(1), eq(0), eq(1), isNull());
    }

    @Test
    @DisplayName("房源已删除：计数丢弃，不报失败")
    void execute_housingDeleted_dropsCounter() throws Exception {
        mockLockAndScan(List.of("housing:view:5"));
        when(valueOps.getAndDelete("housing:view:5")).thenReturn("3");
        when(housingMapper.update(isNull(), any())).thenReturn(0);

        task.execute();

        verify(valueOps, never()).increment(anyString(), anyLong());
        verify(taskLogService).record(anyString(), any(), anyLong(), eq(1), eq(1), eq(0), isNull());
    }

    @Test
    @DisplayName("无计数键：空转成功")
    void execute_noKeys_noop() throws Exception {
        mockLockAndScan(List.of());

        task.execute();

        verify(housingMapper, never()).update(any(), any());
        verify(taskLogService).record(anyString(), any(), anyLong(), eq(0), eq(0), eq(0), isNull());
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
