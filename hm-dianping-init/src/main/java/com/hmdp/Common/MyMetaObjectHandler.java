package com.hmdp.Common;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.tomcat.jni.Local;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class MyMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        log.info("公共字段自动填充i");

        metaObject.setValue("createTime", LocalDateTime.now());
        metaObject.setValue("updateTime", LocalDateTime.now());


        //metaObject.setValue("createUser",new Long(1));
        //metaObject.setValue("updateUser",new Long(1));
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.info("公共字段自动填充u");
        metaObject.setValue("updateTime", LocalDateTime.now());

        //metaObject.setValue("updateUser",new Long(1));
    }
}