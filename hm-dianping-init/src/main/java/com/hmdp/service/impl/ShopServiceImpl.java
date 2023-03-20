package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import io.lettuce.core.api.sync.RedisGeoCommands;
import org.springframework.data.geo.Distance;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //利用redis命令模拟缓存击穿加锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //调用工具包，防止拆箱后为null
        return BooleanUtil.isTrue(flag);
    }

    //利用redis命令模拟缓存击穿解锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThourgh(id);

        //利用逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);

        //调用自己封装的方法
        Shop shop = cacheClient.queryWithMutex("cache:shop:", id, Shop.class, this::getById, 30L, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    //基于热点key,互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {

        //1.根据id从数据库中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        //2.判断缓存是否存在
        if (StringUtils.isNotBlank(shopJson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //从缓存中查到空数据
        if ("".equals(shopJson)) {
            return null;
        }
        String key = "lock:shop:" + id;
        //4.未命中缓存，尝试获取锁
        Shop shop = null;
        try {
            boolean lock = tryLock(key);
            if (!lock) {
                //获取锁失败，重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取锁成功,先复查redis，如果有，则直接返回
            String s = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
            if (s != null) {
                shop = JSONUtil.toBean(s, Shop.class);
                return shop;
            }
            //获取锁成功,重新构建业务，这里就是查数据库
            shop = this.getById(id);
            //5.数据库不存在，返回错误
            if (shop == null) {
                //解决缓存穿透
                stringRedisTemplate.opsForValue().set("cache:shop:" + id, "", 3, TimeUnit.MINUTES);
                return null;
            }
            //6.数据库存在，写入redis
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            stringRedisTemplate.delete(key);
        }
        //7.返回
        return shop;
    }

    //获得线程池
    private static final ExecutorService CACHE_EXECUTOR_SERVICE = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        //1.提交id，从redis查询数据
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        //2.判断缓存是否命中
        if (StringUtils.isBlank(shopJson)) {
            //3.未命中，直接返回空
            return null;
        }
        //4.命中,查看缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((String) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.1 未过期，直接返回店铺信息
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        //4.2过期，需要重建缓存
        String lockKey = "lock:shop:" + id;
        //5.重建缓存
        //5.1获取锁
        boolean islock = tryLock(lockKey);
        //5.2判断是否获取锁成功
        if (islock) {
            String s = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
            if (s != null) {
                shop = JSONUtil.toBean(s, Shop.class);
            }
            //5.4成功，开启独立线程，重建
            CACHE_EXECUTOR_SERVICE.submit(() -> {
                //重建缓存
                Shop shop1 = this.getById(id);
                RedisData redisData1 = new RedisData();
                redisData1.setData(shop1);
                redisData1.setExpireTime(LocalDateTime.now().plusMinutes(30));
                stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(redisData1));
                //6.释放锁
                stringRedisTemplate.delete("lock:shop:" + id);
            });
            return shop;
        }
        //7.返回旧对象
        return shop;
    }

    //封装查询店铺信息缓存穿透
    public Shop queryWithPassThourgh(Long id) {
        //1.根据id从数据库中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        //2.判断是否存在
        if (StringUtils.isNotBlank(shopJson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //从缓存中查到空数据
        if ("".equals(shopJson)) {
            return null;
        }

        //4.不存在，查询数据库
        Shop shop = this.getById(id);

        //5.数据库不存在，返回错误
        if (shop == null) {
            //解决缓存穿透
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, "", 3, TimeUnit.MINUTES);
            return null;
        }
        //6.数据库存在，写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    @Override
    public Result Update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("没有查询到店铺信息");
        }
        //1.先更新数据库
        this.updateById(shop);

        //2.再删除缓存
        stringRedisTemplate.delete("cache:shop:" + shop.getId());
        return Result.ok();
    }

//    @Override
//    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
//        // 1.判断是否需要根据坐标查询
//        if (x == null || y == null) {
//            // 不需要坐标查询，按数据库查询
//            Page<Shop> page = query()
//                    .eq("type_id", typeId)
//                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
//            // 返回数据
//            return Result.ok(page.getRecords());
//        }
//
//        // 2.计算分页参数
//        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
//        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
//
//        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
//        String key = SHOP_GEO_KEY + typeId;
//        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
//                .search(
//                        key,
//                        GeoReference.fromCoordinate(x, y),
//                        new Distance(5000),
//                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
//                );
//        // 4.解析出id
//        if (results == null) {
//            return Result.ok(Collections.emptyList());
//        }
//        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
//        if (list.size() <= from) {
//            // 没有下一页了，结束
//            return Result.ok(Collections.emptyList());
//        }
//        // 4.1.截取 from ~ end的部分
//        List<Long> ids = new ArrayList<>(list.size());
//        Map<String, Distance> distanceMap = new HashMap<>(list.size());
//        list.stream().skip(from).forEach(result -> {
//            // 4.2.获取店铺id
//            String shopIdStr = result.getContent().getName();
//            ids.add(Long.valueOf(shopIdStr));
//            // 4.3.获取距离
//            Distance distance = result.getDistance();
//            distanceMap.put(shopIdStr, distance);
//        });
//        // 5.根据id查询Shop
//        String idStr = StrUtil.join(",", ids);
//        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
//        for (Shop shop : shops) {
//            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
//        }
//        // 6.返回
//        return Result.ok(shops);
//        }
    }

