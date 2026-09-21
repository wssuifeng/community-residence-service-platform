# Redis 雪崩降级方案审核报告

> 审核时间：2026-09-06  
> 审核对象：用户提出的 Redis 雪崩降级方案（长连接场景）  
> 审核人：AI（Claude Code）

## 1. 方案总览

**核心理念**：保大头（正常用户），牺牲长尾（异常/恶意流量）

**六大策略**：
1. 降级鉴权策略：长连接建立时 DB 一次鉴权，会话期免检
2. 黑名单处理：DB 双写，Redis 不可用降级查 DB
3. 频控与防雪崩：鉴权失败即拉黑，非频控阈值
4. 会话生命周期：10-30 分钟到期强制重连
5. 权限变更处理：主动踢人（Kick）强制重连
6. 设计理念：优先核心消息通道可用，权限变更允许延迟

---

## 2. 逐条审核

### 2.1 降级鉴权策略 ✅ 优秀

**方案**：Redis 不可用时，长连接建立（CONNECT/握手）阶段通过 DB 一次鉴权，后续业务请求直接走长连接会话，不再频繁鉴权。

**审核评价**：
- ✅ **核心优点**：将鉴权压力从"每次消息"降为"每次连接"，DB 压力从秒级降为分钟级（会话生命周期）
- ✅ **符合长连接特性**：WebSocket 本身就是"连接时鉴权，会话期信任"的模型
- ✅ **降级平滑**：正常用户体感无差异（只是从 Redis 鉴权变为 DB 鉴权）

**潜在风险与建议**：
- ⚠️ **风险 1：会话劫持**  
  如果用户在会话期内被删除账号/封禁，已建立的长连接仍可通信（直到会话到期）。  
  **建议**：补充"紧急踢人"机制（见 2.5 节评审）。

- ⚠️ **风险 2：DB 鉴权性能**  
  假设 50 并发用户，会话生命周期 30min，则 DB 鉴权 QPS = 50 / (30 * 60) ≈ 0.028 QPS，完全可控。  
  但如果用户频繁断连（网络不稳定），DB 鉴权 QPS 会飙升。  
  **建议**：补充"短时重连豁免"机制（见 2.4 节评审）。

---

### 2.2 黑名单处理 ✅ 优秀

**方案**：黑名单数据 DB 双写（Redis + DB），Redis 不可用时降级查 DB。

**审核评价**：
- ✅ **核心优点**：彻底解决"Redis 不可用 = 全系统不可用"的严重问题
- ✅ **数据一致性**：双写保证黑名单权威来源在 DB，Redis 仅作缓存
- ✅ **降级优雅**：用户登出后令牌仍可失效（只是查询变慢）

**实现细节建议**：

#### 2.2.1 黑名单表设计
```sql
CREATE TABLE auth_token_blacklist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    jti VARCHAR(64) NOT NULL UNIQUE COMMENT 'JWT ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    expire_time DATETIME NOT NULL COMMENT '令牌自然过期时间',
    blacklist_time DATETIME NOT NULL COMMENT '加入黑名单时间',
    reason VARCHAR(255) COMMENT '原因：LOGOUT/PERMISSION_CHANGE/FORCE_OFFLINE',
    INDEX idx_jti (jti),
    INDEX idx_expire_time (expire_time)
) COMMENT='令牌黑名单（DB 权威来源）';
```

#### 2.2.2 双写逻辑（登出时）
```java
@Transactional
public void logout(String token) {
    String jti = jwtUtil.extractJti(token);
    Date expireTime = jwtUtil.extractExpiration(token);
    Long userId = jwtUtil.extractUserId(token);
    
    // 1. 写入 DB（权威来源）
    TokenBlacklist blacklist = new TokenBlacklist();
    blacklist.setJti(jti);
    blacklist.setUserId(userId);
    blacklist.setExpireTime(expireTime);
    blacklist.setBlacklistTime(new Date());
    blacklist.setReason("LOGOUT");
    blacklistMapper.insert(blacklist);
    
    // 2. 写入 Redis（缓存）
    try {
        long ttl = expireTime.getTime() - System.currentTimeMillis();
        redisTemplate.opsForValue().set(
            "token:blacklist:" + jti, 
            "1", 
            ttl, 
            TimeUnit.MILLISECONDS
        );
    } catch (RedisConnectionException e) {
        log.warn("Redis 不可用，黑名单已写入 DB，登出成功但缓存失效");
        // 不抛异常，保证登出成功
    }
}
```

#### 2.2.3 黑名单校验（降级逻辑）
```java
public boolean isBlacklisted(String jti) {
    try {
        // 优先查 Redis
        Boolean cached = redisTemplate.hasKey("token:blacklist:" + jti);
        if (cached != null) {
            return cached;
        }
        
        // Redis 未命中，查 DB 并回种
        TokenBlacklist blacklist = blacklistMapper.selectOne(
            Wrappers.<TokenBlacklist>lambdaQuery()
                .eq(TokenBlacklist::getJti, jti)
                .gt(TokenBlacklist::getExpireTime, new Date()) // 只查未过期的
        );
        
        if (blacklist != null) {
            // 回种到 Redis
            try {
                long ttl = blacklist.getExpireTime().getTime() - System.currentTimeMillis();
                redisTemplate.opsForValue().set("token:blacklist:" + jti, "1", ttl, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {}
            return true;
        }
        return false;
        
    } catch (RedisConnectionException e) {
        log.warn("Redis 不可用，降级查 DB 黑名单：jti={}", jti);
        
        // 降级查 DB
        TokenBlacklist blacklist = blacklistMapper.selectOne(
            Wrappers.<TokenBlacklist>lambdaQuery()
                .eq(TokenBlacklist::getJti, jti)
                .gt(TokenBlacklist::getExpireTime, new Date())
        );
        return blacklist != null;
    }
}
```

#### 2.2.4 定时清理过期黑名单（减轻 DB 压力）
```java
@Scheduled(cron = "0 0 3 * * ?") // 每天凌晨 3:00
public void cleanExpiredBlacklist() {
    int deleted = blacklistMapper.delete(
        Wrappers.<TokenBlacklist>lambdaQuery()
            .lt(TokenBlacklist::getExpireTime, new Date())
    );
    log.info("清理过期黑名单：{} 条", deleted);
}
```

**审核结论**：✅ 方案优秀，实现细节已补充。

---

### 2.3 频控与防雪崩 ✅ 优秀，但需补充细节

**方案**：鉴权失败一次即拉黑该 IP/用户，不设"每秒 100 次"频控阈值。

**审核评价**：
- ✅ **核心优点**："失败即拉黑"从源头阻断异常流量，保护 DB
- ✅ **简单粗暴有效**：避免恶意请求反复冲击 DB

**潜在风险与建议**：

#### 风险 1：误伤正常用户
**场景**：用户输错密码一次 → 被拉黑 → 正确密码也无法登录  
**建议**：区分"认证失败"与"鉴权失败"
- **认证失败**（密码错误、用户不存在）：允许 5 次/5 分钟，超过则拉黑（传统频控）
- **鉴权失败**（令牌伪造、过期令牌反复使用）：一次即拉黑（异常流量）

#### 风险 2：拉黑时长过长
**场景**：用户被误拉黑后，永久无法访问  
**建议**：拉黑分级
- **轻度拉黑**：5 分钟（认证失败 5 次）
- **重度拉黑**：1 小时（鉴权失败、疑似攻击）
- **永久拉黑**：需管理员手动解封（恶意扫描、SQL 注入尝试）

#### 实现建议（本地拉黑 + Redis 同步）
```java
// 本地拉黑缓存（避免 Redis 不可用时拉黑失效）
private static final LoadingCache<String, Long> LOCAL_BLACKLIST = Caffeine.newBuilder()
    .expireAfterWrite(1, TimeUnit.HOURS)
    .maximumSize(10000)
    .build(key -> null);

public void blacklistIpOrUser(String key, String reason, Duration duration) {
    long expireTime = System.currentTimeMillis() + duration.toMillis();
    
    // 1. 本地拉黑（立即生效）
    LOCAL_BLACKLIST.put(key, expireTime);
    
    // 2. Redis 拉黑（集群同步）
    try {
        redisTemplate.opsForValue().set(
            "blacklist:" + key, 
            reason, 
            duration.toMillis(), 
            TimeUnit.MILLISECONDS
        );
    } catch (RedisConnectionException e) {
        log.warn("Redis 不可用，仅本地拉黑：key={}", key);
    }
    
    // 3. DB 记录（审计日志）
    BlacklistLog log = new BlacklistLog();
    log.setKey(key);
    log.setReason(reason);
    log.setExpireTime(new Date(expireTime));
    blacklistLogMapper.insert(log);
}

public boolean isBlacklisted(String key) {
    // 1. 本地缓存优先（最快）
    Long expireTime = LOCAL_BLACKLIST.getIfPresent(key);
    if (expireTime != null && expireTime > System.currentTimeMillis()) {
        return true;
    }
    
    // 2. Redis 查询（集群共享）
    try {
        Boolean exists = redisTemplate.hasKey("blacklist:" + key);
        if (Boolean.TRUE.equals(exists)) {
            // 同步到本地缓存
            Long ttl = redisTemplate.getExpire("blacklist:" + key, TimeUnit.MILLISECONDS);
            LOCAL_BLACKLIST.put(key, System.currentTimeMillis() + (ttl != null ? ttl : 3600000));
            return true;
        }
    } catch (RedisConnectionException e) {
        log.warn("Redis 不可用，仅本地拉黑生效");
    }
    
    return false;
}
```

**审核结论**：✅ 方案优秀，需补充拉黑分级与误伤防护。

---

### 2.4 会话生命周期 ✅ 优秀，需补充短时重连豁免

**方案**：长连接会话 10-30 分钟生命周期，到期服务端主动断连，客户端重连时重新 DB 鉴权。

**审核评价**：
- ✅ **核心优点**：将"缓存同步"压力从秒级降为半小时一次，DB 鉴权 QPS 降至可忽略
- ✅ **符合实际场景**：用户在线时长通常 > 30 分钟，断连频率低

**潜在风险与建议**：

#### 风险：网络抖动导致频繁重连
**场景**：用户网络不稳定，每 1 分钟断连一次 → DB 鉴权 QPS 飙升  
**建议**：补充"短时重连豁免"机制

```java
// 会话断开时缓存用户状态（5 分钟有效）
public void onDisconnect(String sessionId, Long userId) {
    try {
        redisTemplate.opsForValue().set(
            "session:reconnect:" + userId, 
            sessionId, 
            5, 
            TimeUnit.MINUTES
        );
    } catch (RedisConnectionException e) {
        // Redis 不可用时不缓存，降级为每次重连都鉴权
    }
}

// 重连时检查豁免
public boolean canReconnectWithoutAuth(Long userId, String oldSessionId) {
    try {
        String cachedSessionId = redisTemplate.opsForValue().get("session:reconnect:" + userId);
        return oldSessionId.equals(cachedSessionId);
    } catch (RedisConnectionException e) {
        return false; // Redis 不可用时不豁免，走 DB 鉴权
    }
}
```

**审核结论**：✅ 方案优秀，建议补充短时重连豁免。

---

### 2.5 权限变更处理 ✅ 优秀，需补充集群广播细节

**方案**：权限变更时主动踢人（Kick），强制重连获取新权限；维护 userId -> SessionId 本地映射，集群通过 MQ 广播。

**审核评价**：
- ✅ **核心优点**：权限变更即时生效（不等会话到期）
- ✅ **符合实际需求**：R47 要求"权限变更后立即生效"

**实现细节建议**：

#### 单实例实现（当前架构）
```java
// 维护本地映射
private static final ConcurrentMap<Long, String> USER_SESSION_MAP = new ConcurrentHashMap<>();

// WebSocket 连接建立时注册
public void onConnect(Long userId, String sessionId) {
    USER_SESSION_MAP.put(userId, sessionId);
    log.info("用户上线：userId={}, sessionId={}", userId, sessionId);
}

// 权限变更时踢人
public void kickUser(Long userId, String reason) {
    String sessionId = USER_SESSION_MAP.get(userId);
    if (sessionId != null) {
        simpMessagingTemplate.convertAndSendToUser(
            sessionId, 
            "/queue/kick", 
            new KickMessage(reason)
        );
        // 服务端主动关闭连接
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session != null) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
        USER_SESSION_MAP.remove(userId);
        log.info("踢出用户：userId={}, reason={}", userId, reason);
    }
}
```

#### 多实例扩展（未来）
```java
// Redis Pub/Sub 广播踢人指令
public void kickUserCluster(Long userId, String reason) {
    KickCommand cmd = new KickCommand(userId, reason);
    redisTemplate.convertAndSend("channel:kick", cmd);
}

// 每个实例订阅踢人指令
@RedisListener(topics = "channel:kick")
public void onKickCommand(KickCommand cmd) {
    kickUser(cmd.getUserId(), cmd.getReason());
}
```

**审核结论**：✅ 方案优秀，单实例实现简单，多实例扩展清晰。

---

### 2.6 设计理念 ✅ 优秀

**理念**：保大头（正常用户），牺牲长尾（异常流量）；优先核心消息通道可用，权限变更允许延迟。

**审核评价**：
- ✅ **务实**：不追求降级期间的强一致性，符合实际需求优先级
- ✅ **可落地**：明确了权衡取舍（可用性 > 一致性）

---

## 3. 整体方案评分

| 维度 | 得分 | 满分 | 评价 |
|------|------|------|------|
| 理论正确性 | 10 | 10 | 降级逻辑合理，无明显理论缺陷 |
| 可实施性 | 9 | 10 | 核心逻辑清晰，需补充细节（短时重连豁免、拉黑分级） |
| 安全性 | 9 | 10 | 双写保证黑名单可用，需补充误伤防护 |
| 性能 | 10 | 10 | DB 压力从秒级降为分钟级，完全可控 |
| 可维护性 | 9 | 10 | 本地拉黑 + Redis 同步逻辑略复杂，但可接受 |
| **总分** | **47** | **50** | **优秀** |

---

## 4. 发现的"坑"与修正建议

### 坑 1：误伤正常用户 ⚠️ 中等风险

**问题**："鉴权失败即拉黑"可能误伤输错密码的正常用户。

**修正**：
- 区分"认证失败"（允许 5 次/5 分钟）与"鉴权失败"（一次即拉黑）
- 拉黑分级（5 分钟 / 1 小时 / 永久）
- 管理员手动解封接口

---

### 坑 2：网络抖动导致 DB 压力飙升 ⚠️ 中等风险

**问题**：用户网络不稳定频繁断连 → 每次重连都 DB 鉴权 → DB 压力飙升。

**修正**：
- 补充"短时重连豁免"（5 分钟内重连免鉴权）
- Redis 不可用时豁免机制失效，降级为每次鉴权（权衡）

---

### 坑 3：集群环境下的会话映射不一致 ⚠️ 低风险（当前单实例）

**问题**：多实例环境下，用户连接在实例 A，权限变更在实例 B，本地映射无法找到该用户。

**修正**：
- 使用 Redis Pub/Sub 广播踢人指令（已在 2.5 节补充）
- 或使用 Redis 存储 userId -> SessionId 映射（集群共享）

---

### 坑 4：会话劫持窗口期 ⚠️ 低风险

**问题**：用户在会话期内被封禁，已建立的长连接仍可通信（直到会话到期或主动踢人）。

**修正**：
- 封禁操作必须触发"主动踢人"（已在 2.5 节补充）
- 会话期内的消息发送，可增加"轻量级权限校验"（仅检查用户状态，不走完整鉴权）

---

### 坑 5：DB 黑名单表膨胀 ⚠️ 低风险

**问题**：黑名单数据只增不删，DB 表无限膨胀。

**修正**：
- 定时清理过期黑名单（已在 2.2.4 节补充）
- 或使用分区表（按月分区，自动删除旧分区）

---

## 5. 审核结论

**总体评价**：✅ **方案优秀，可落地，需补充 5 处细节**

**优点**：
1. ✅ 核心理念务实（保大头，牺牲长尾）
2. ✅ 双写保证黑名单降级可用（彻底解决原方案"过于严格"问题）
3. ✅ DB 压力可控（秒级降为分钟级）
4. ✅ 权限变更主动踢人（即时生效）

**需补充的细节**：
1. 📝 拉黑分级与误伤防护（认证失败 vs 鉴权失败）
2. 📝 短时重连豁免（5 分钟内免鉴权）
3. 📝 会话劫持防护（封禁必须触发踢人）
4. 📝 集群环境踢人广播（Redis Pub/Sub）
5. 📝 定时清理过期黑名单

**建议后续动作**：
1. 将本审核报告的实现细节补充到架构设计文档 §3.1 / §4 / §9.1
2. 数据库设计阶段补充 `auth_token_blacklist` 表设计
3. 接口设计阶段补充"踢人接口"、"解封接口"
4. 测试阶段重点验证：网络抖动下的 DB 压力、误伤防护、权限变更即时生效

---

## 6. 与原方案对比

| 维度 | 原方案（Redis 不可用拒绝） | 新方案（DB 双写降级） | 对比 |
|------|-------------------------|-------------------|------|
| 可用性 | ❌ Redis 崩溃 = 全系统不可用 | ✅ 降级查 DB，功能可用 | **新方案优** |
| 性能 | ✅ Redis 查询极快 | ⚠️ 降级时 DB 查询略慢，但可控 | 原方案略优，但新方案可接受 |
| 一致性 | ✅ Redis 为唯一来源，强一致 | ✅ DB 为权威来源，Redis 缓存，最终一致 | 新方案更可靠 |
| 复杂度 | ✅ 实现简单 | ⚠️ 双写 + 降级逻辑略复杂 | 原方案略优 |
| 安全性 | ✅ 黑名单必达 | ✅ 黑名单必达（DB 兜底） | 持平 |

**结论**：新方案在可用性维度大幅优于原方案，性能与复杂度的小幅劣化可接受。✅ **强烈推荐采纳新方案。**
