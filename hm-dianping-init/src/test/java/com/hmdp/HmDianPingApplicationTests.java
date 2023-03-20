package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {
    @Resource
    private RedissonClient redissonClient;

    RLock lock = redissonClient.getLock("lock:order:");


    @Test
    void method1() {
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("获取锁失败，1");
            return;
        }
        try {
            log.info("获取锁成功，1");
            method2();
        } finally {
            log.info("释放锁，1");
            lock.unlock();
        }
    }

    void method2() {
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("获取锁失败, 2");
            return;
        }
        try {
            log.info("获取锁成功，2");
        } finally {
            log.info("释放锁，2");
            lock.unlock();
        }
    }
}


