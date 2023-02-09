package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CatchClient {
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用于缓存重建的线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CatchClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将KV对保存到redis中
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 基于逻辑过期保存至redis
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 封装了利用缓存空值防止缓存穿透的方法
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithPathThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //从redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            //存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否为空值
        if (json != null) {
            return null;
        }
        //不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //不存在，返回错误
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 使用逻辑过期的方法解决缓存击穿
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //从redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            //不存在，直接返回
            log.info("缓存为空");
            return null;
        }
        //命中，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，返回
            return r;
        }
        //过期，需要重建缓存
        String lockKey = lockPrefix + id;
        boolean isLocked = tryLock(lockKey);
        if (isLocked) {
            //获取成功，先检查是否存在redis缓存
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                //存在，直接返回
                return JSONUtil.toBean(json, type);
            }
            //不存在，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R apply = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, apply, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //获取失败，返回过期的商铺信息
        return r;
    }

    /**
     * 使用加互斥锁的方式来访问店铺
     * @param id
     * @return
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //从redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            //存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null) {
            //如果是空值，就返回空信息
            return null;
        }
        //实现缓存重建
        //获取互斥锁
        String lockKey = lockPrefix + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if (!isLock) {
                //失败了就休眠并重试
                Thread.sleep(50);
                queryWithMutex(keyPrefix, lockPrefix, id, type, dbFallback, time, unit);
            }
            //成功则查询
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                //存在，直接返回
                return JSONUtil.toBean(json, type);
            }
            r = dbFallback.apply(id);
            if (r == null) {
                //不存在，将空值写入redis并返回错误信息
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在，先写入redis，再返回
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return r;
    }

    /**
     * 加锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 解锁
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
