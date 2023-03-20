package com.hmdp.utils;

import java.sql.Time;

public interface ILock {
    boolean tryLock(Long timeoutSec);

    void unlock();
}
