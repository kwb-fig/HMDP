package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithMutex(String prefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){

        //1.根据id从数据库中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(prefix + id);

        //2.判断缓存是否存在
        if(StringUtils.isNotBlank(Json)){
            //3.存在，直接返回
            return JSONUtil.toBean(Json, type);
        }
        //从缓存中查到空数据
        if("".equals(Json)){
            return null;
        }
        String key="lock:shop:"+id;
        //4.未命中缓存，尝试获取锁
        R r=null;
        try {
            boolean lock = tryLock(key);
            if (!lock){
                //获取锁失败，重试
                Thread.sleep(50);
                return queryWithMutex(prefix+id,id,type,dbFallback,time,unit);
            }
            //获取锁成功,先复查redis，如果有，则直接返回
            String s = stringRedisTemplate.opsForValue().get(prefix + id);
            if(s!=null){
                r= JSONUtil.toBean(s, type);
                return r;
            }
            //获取锁成功,重新构建业务，这里就是查数据库
            r = dbFallback.apply(id);
            //5.数据库不存在，返回错误
            if(r==null){
                //解决缓存穿透
                stringRedisTemplate.opsForValue().set("cache:shop:"+id,"",3, TimeUnit.MINUTES);
                return null;
            }
            //6.数据库存在，写入redis
            this.set(prefix+id,r,time,unit);
        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            this.unLock(key);
        }
        //7.返回
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    this.unLock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //调用工具包，防止拆箱后为null
        return BooleanUtil.isTrue(flag);
    }

    //利用redis命令模拟缓存击穿解锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
