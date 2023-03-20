package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.直接从redis中查询分类
        List<String> list = stringRedisTemplate.opsForList().range("cache:TypeList", 0, -1);
        List<ShopType> shopTypeList=new ArrayList<>();

        //2.如果有，就直接返回
        if(!list.isEmpty()){
            for (String s : list) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                shopTypeList.add(shopType);
            }
            return Result.ok(shopTypeList);
        }
        //2.如果没有，就从数据库中查
        shopTypeList= query().orderByDesc("sort").list();

        //3.如果数据库没有，直接返回错误
        if(shopTypeList.isEmpty()){
            return Result.fail("不存在分类");
        }

        //4.将数据添加进redis
        for (ShopType shopType : shopTypeList) {
            list.add(JSONUtil.toJsonStr(shopType));
        }
        stringRedisTemplate.opsForList().leftPushAll("cache:TypeList", list);
        //5.返回
        return Result.ok(shopTypeList);
    }
}
