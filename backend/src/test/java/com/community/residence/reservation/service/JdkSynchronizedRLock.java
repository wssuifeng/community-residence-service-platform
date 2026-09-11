package com.community.residence.reservation.service;

import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.ObjectListener;

import java.util.concurrent.TimeUnit;

/**
 * ReentrantLock 适配的 RLock：并发测试在无 Redis 依赖的纯单测环境下
 * 提供真实互斥语义（tryLock → unlock 跨越整个业务临界区）。
 * 其余能力为测试未触达的空实现，仅满足接口编译要求。
 */
public final class JdkSynchronizedRLock implements RLock {

    private final java.util.concurrent.locks.ReentrantLock delegate = new java.util.concurrent.locks.ReentrantLock();

    public JdkSynchronizedRLock(Object ignored) {
        // 构造参数仅为调用处可读性保留，互斥由内部 ReentrantLock 承载
    }

    @Override
    public boolean tryLock(long waitTime, TimeUnit unit) throws InterruptedException {
        return delegate.tryLock(waitTime, unit);
    }

    @Override
    public void unlock() {
        delegate.unlock();
    }

    @Override
    public String getName() {
        return "test-lock";
    }

    /* ---- 以下为测试未触达的实现/空实现 ---- */

    @Override public void lock() { delegate.lock(); }
    @Override public void lock(long leaseTime, TimeUnit unit) { delegate.lock(); }
    @Override public void lockInterruptibly() throws InterruptedException { delegate.lockInterruptibly(); }
    @Override public void lockInterruptibly(long leaseTime, TimeUnit unit) throws InterruptedException { delegate.lockInterruptibly(); }
    @Override public boolean tryLock() { return delegate.tryLock(); }
    @Override public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        return delegate.tryLock(waitTime, unit);
    }
    @Override public boolean forceUnlock() { return true; }
    @Override public boolean isLocked() { return false; }
    @Override public boolean isHeldByThread(long threadId) { return false; }
    @Override public boolean isHeldByCurrentThread() { return false; }
    @Override public int getHoldCount() { return 0; }
    @Override public long remainTimeToLive() { return -1; }
    @Override public java.util.concurrent.locks.Condition newCondition() {
        throw new UnsupportedOperationException();
    }
    @Override public int addListener(ObjectListener listener) { return 0; }
    @Override public void removeListener(int listenerId) { }

    @Override public RFuture<Boolean> tryLockAsync() { return null; }
    @Override public RFuture<Boolean> tryLockAsync(long waitTime) { return null; }
    @Override public RFuture<Boolean> tryLockAsync(long waitTime, TimeUnit unit) { return null; }
    @Override public RFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) { return null; }
    @Override public RFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit, long currentThreadId) { return null; }
    @Override public RFuture<Void> lockAsync() { return null; }
    @Override public RFuture<Void> lockAsync(long leaseTime) { return null; }
    @Override public RFuture<Void> lockAsync(long leaseTime, TimeUnit unit) { return null; }
    @Override public RFuture<Void> lockAsync(long leaseTime, TimeUnit unit, long currentThreadId) { return null; }
    @Override public RFuture<Void> unlockAsync() { return null; }
    @Override public RFuture<Void> unlockAsync(long currentThreadId) { return null; }
    @Override public RFuture<Boolean> forceUnlockAsync() { return null; }
    @Override public RFuture<Boolean> isLockedAsync() { return null; }
    @Override public RFuture<Boolean> isHeldByThreadAsync(long threadId) { return null; }
    @Override public RFuture<Integer> getHoldCountAsync() { return null; }
    @Override public RFuture<Long> remainTimeToLiveAsync() { return null; }
    @Override public RFuture<Integer> addListenerAsync(ObjectListener listener) { return null; }
    @Override public RFuture<Void> removeListenerAsync(int listenerId) { return null; }
}
