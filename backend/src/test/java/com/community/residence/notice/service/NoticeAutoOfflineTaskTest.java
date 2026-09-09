package com.community.residence.notice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.schedule.service.TaskLogService;
import com.community.residence.schedule.task.NoticeAutoOfflineTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 公告自动下线任务测试：过期 PUBLISHED 公告置 WITHDRAWN，任务日志落库 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NoticeAutoOfflineTask 单元测试")
class NoticeAutoOfflineTaskTest {

    @Mock
    private NoticeMapper noticeMapper;
    @Mock
    private TaskLogService taskLogService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private NoticeAutoOfflineTask task;

    /* 纯 Mockito 环境无 Spring 容器，需手动注册实体元数据供 Lambda 条件构造器解析列名 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Notice.class);
    }

    @Test
    @DisplayName("过期公告：批量置 WITHDRAWN 并记日志")
    void execute_offlineExpiredNotices() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        when(noticeMapper.update(isNull(), any())).thenReturn(2);

        task.execute();

        verify(noticeMapper).update(isNull(), any(Wrapper.class));
        verify(taskLogService).record(anyString(), any(), anyLong(), eq(2), eq(2), eq(0), isNull());
    }

    @Test
    @DisplayName("无过期公告：更新 0 条，正常记日志")
    void execute_noExpired_zeroCount() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        when(noticeMapper.update(isNull(), any())).thenReturn(0);

        task.execute();

        verify(taskLogService).record(anyString(), any(), anyLong(), eq(0), eq(0), eq(0), isNull());
    }
}
