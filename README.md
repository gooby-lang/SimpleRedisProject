## DAY1：

+ 基于Redis保存token的用户登录功能开发
  + 考虑到多个服务器时共享session较麻烦，于是使用Redis来保存用户token的方式来进行用户登录
  + 在每次用户访问页面或者发出请求时刷新token

## DAY2：

+ 基于Redis中间件实现缓存，来加快用户访问页面的速度
  + 只要在service层中，将每次从数据库得到数据前先对redis中数据库进行查询即可
+ 考虑到在多线程条件下的对数据的查询，如果查询和修改同时发生，应当保持数据的原子性
  + 于是我们考虑先修改数据库，再删除缓存。因为这样失误的情况只有，当缓存失效时，先去查询数据库，得到旧数据，当将要把旧数据写入缓存时，其他线程开始修改数据库。而写缓存的速度是远大于修改数据库的，因此能插入进来的概率极低。
+ 为了预防缓存穿透，当用户请求的数据在缓存和数据库中都不存在，并且他不断发生这样的请求时，会给数据库带来巨大的压力
  + （缓存null值）于是我们在查询的过程中，加入判断是否为空值，如果是空值，则加入缓存，并且给一个较短的TTL
+ 解决缓存击穿问题
  + 基于互斥锁方式：需要在缓存重建时添加一个互斥锁，如果能请求到互斥锁就添加缓存，否则就休眠一段时间再访问缓存，最后释放互斥锁
  + 基于逻辑过期方式：首先查找缓存，如果没有命中，则返回空，否则先判断缓存是否过期，如果没有过期就返回商铺信息，如果过期了就尝试获取互斥锁，如果没有获取，就返回旧的商铺信息，否则开启一个新线程写入redis，并设置逻辑过期时间
+ 由于工具过多，我们考虑写一个工具类来整合以上工具，这里用到了函数式编程

## DAY3：
+ 给订单一个全局唯一ID
  + 我们使用基于Redis的全局唯一id生成器
+ 在秒杀过程中，对于并发问题的解决
  + （乐观锁）利用数据库中update-where的原子性，我们在减库存的时候判断下当前是否大于零即可
+ 为了防止黄牛，使用了一人一单
  + （悲观锁）使用synchronized关键字将创建订单的过程加锁，并且为了防止事务未提交就释放锁，使用了aspectj工具包获取事务中的对象，调用对象中的方法，来解决这个问题。但是这个同样会造成一个问题：集群下的线程安全问题

## DAY4：

+   解决集群下的线程安全问题
    +   可以基于redis的Lua脚本实现分布式锁来解决集群下的线程安全问题，利用lua脚本的原子性能够保证检查和删除key的操作的原子性
+   使用redisson解决可重入问题
    +   利用hash结构记录线程id和重入次数
+   使用redisson解决可重试问题
    +   利用信号量和pubsub功能实现等待、唤醒，获取锁失败的重试机制
+   使用redisson解决超时续约问题
    +   利用watchdog，每隔一段时间（releaseTime/3），重置超时时间
+   使用redisson解决主从一致性问题
    +   利用multilock，多个独立的redis节点，必须在所有结点都获取重入锁，才算获取锁成功

## DAY5：

+   秒杀的优化
    +   利用阻塞队列异步下单来加快下单效率
    +   1、利用redis完成库存余量，一人一单的判断，完成抢单业务
    +   2、再将下单业务放入阻塞队列，利用线程异步下单
    +   存在内存限制的问题，还有数据安全的问题

## DAY6：

+   使用feed流实现用户关注的推送功能