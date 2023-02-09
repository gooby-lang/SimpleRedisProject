package com.hmdp.utils;

/**
 * @author never give up
 * @date 2023/2/9
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 超时时间
     * @return  true表示取锁成功，否则失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
