package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    String name;
    StringRedisTemplate stringRedisTemplate;

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String threadId=ID_PREFIX+Thread.currentThread().getId();
        Boolean aBoolean = stringRedisTemplate.opsForValue()
                .setIfAbsent("lock:" + name + userId, threadId, timeoutSec, TimeUnit.SECONDS);
        //拆箱,防null
        boolean aTrue = BooleanUtil.isTrue(aBoolean);
        return aTrue;
    }

    @Override
    public void unlock() {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();

        //释放锁之前先判断是否为自己的锁，防止误删
        String threadId=ID_PREFIX+Thread.currentThread().getId();

        String s = stringRedisTemplate.opsForValue().get("lock:" + name + userId);

        if(threadId.equals(s)) {
            stringRedisTemplate.delete("lock:" + name + userId);
        }
    }
}
