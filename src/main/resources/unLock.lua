---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by never give up.
--- DateTime: 2023/2/9 23:16
---

--比较线程标识和锁中的标识是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    --释放锁
    return redis.call('del', KEYS[1])
end
return 0