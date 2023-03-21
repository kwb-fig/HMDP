package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.StringValue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //获取一个单线程,异步处理订单不需要特别快
    private static final ExecutorService SECKILL_EXECUTOR_SERVICE= Executors.newSingleThreadExecutor();

    //在类加载完时就启动
    @PostConstruct
    private void init(){
        //类加载完成就开始就提交任务
        SECKILL_EXECUTOR_SERVICE.submit(new VoucherOrderHandler());
    }

//    //创建阻塞队列
//    //这个一般使用rabbitmq
//    private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024 * 1024);
//
//    //线程任务处理业务逻辑
//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    //获取订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //创建订单
//                    //handlerVoucherOrder(voucherOrder);
//                    proxy.createVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }

//        //创建订单业务
//        private void handlerVoucherOrder(VoucherOrder voucherOrder) {
//            Long userId = voucherOrder.getId();
//            // 创建锁对象
//            // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//            RLock lock = redissonClient.getLock("lock:order:" + userId);
//            // 获取锁
//            boolean isLock = lock.tryLock();
//            // 判断是否获取锁成功
//            if(!isLock){
//                // 获取锁失败，返回错误或重试
//                log.error("不允许重复下单");
//                return;
//            }
//            try {
//                // 获取代理对象（事务）
//                proxy.createVoucherOrder(voucherOrder);
//            } finally {
//                // 释放锁
//                lock.unlock();
//            }
//        }
//    }


    //线程任务处理业务逻辑
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的信息 XREADGROUP  g1 c1 count 1 block 2000 streams.orders >
                    // g1组，消费者为c1，读1条消息,阻塞两秒，从streams.orders队列中读 最近一条为消费的信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获取成功
                    if(list.isEmpty() || list==null){
                        //2.1 如果失败，则表示没有消息，则继续循环
                        continue;
                    }
                    //3.先从list里解析出订单信息
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //3. 如果获取成功,就执行下单操作
                    proxy.createVoucherOrder(voucherOrder);
                    //4.ACK确认  消息队列，组，消息id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",entries.getId());
                } catch (Exception e) {
                    while (true){
                        log.error("处理订单异常", e);
                        handlePendingList();
                    }
                }
            }
        }
    }

    private void handlePendingList(){
        while (true) {
            try {
                //1.获取pendingList的信息 XREADGROUP  g1 c1 count 1 block 2000 streams.orders 0
                // g1组，消费者为c1，读1条消息,阻塞两秒，从streams.orders队列中读 从pending List读
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create("stream.orders", ReadOffset.from("0"))
                );
                //2.判断消息是否获取成功
                if (list.isEmpty() || list == null) {
                    //2.1 如果失败，则表示没有消息，退出
                    break;
                }
                //3.先从list里解析出订单信息
                MapRecord<String, Object, Object> entries = list.get(0);
                Map<Object, Object> values = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //3. 如果获取成功,就执行下单操作
                proxy.createVoucherOrder(voucherOrder);
                //4.ACK确认  消息队列，组，消息id
                stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",entries.getId());
            } catch (Exception e) {
                while (true){
                    log.error("pendinglist异常", e);
                    //pendlist抛异常,打印异常,就直接再次获取,休眠20毫秒
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    IVoucherOrderService proxy;

    //用redis的stream流去发送消息
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        //2.判断秒杀是否开始
        if(LocalDateTime.now().isBefore(beginTime)){
            return Result.fail("抢购还未开始");
        }
        //3.判断秒杀是否结束
        if(LocalDateTime.now().isAfter(endTime)){
            return Result.fail("抢购已经结束");
        }

        Long userId = UserHolder.getUser().getId();

        long orderId = redisIdWorker.nextId("order");
        //4.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        //5.判断结果,1为库存不足,2为重复购买,0正常
        if(r!=0){
            return Result.fail(r==1 ? "库存不足" : "请勿重复购买");
        }
        //在主线程中获得代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //8.返回订单id
        return Result.ok(orderId);
    }

    //基于阻塞队列来创建订单
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        //2.判断秒杀是否开始
//        if(LocalDateTime.now().isBefore(beginTime)){
//            return Result.fail("抢购还未开始");
//        }
//        //3.判断秒杀是否结束
//        if(LocalDateTime.now().isAfter(endTime)){
//            return Result.fail("抢购已经结束");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //4.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        int r = result.intValue();
//        //5.判断结果,1为库存不足,2为重复购买,0正常
//        if(r!=0){
//            return Result.fail(r==1 ? "库存不足" : "请勿重复购买");
//        }
//        //6.为0，将订单保存到阻塞队列中去
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //6.1订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //6.2用户id
//        voucherOrder.setUserId(userId);
//        //6.3代金券id
//        voucherOrder.setVoucherId(voucherId);
//
//        //7.加入阻塞队列
//        orderTasks.add(voucherOrder);
//        //在主线程中获得代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //8.返回订单id
//        return Result.ok(orderId);
//    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        //2.判断秒杀是否开始
//        if(LocalDateTime.now().isBefore(beginTime)){
//            return Result.fail("抢购还未开始");
//        }
//        //3.判断秒杀是否结束
//        if(LocalDateTime.now().isAfter(endTime)){
//            return Result.fail("抢购已经结束");
//        }
//        //4.判断是否有库存
//        Integer stock = seckillVoucher.getStock();
//        if(stock < 1){
//            return Result.fail("优惠券已抢完");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //分布式下使用Redisson
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }

//        //只能在单体项目下实现，集群时不适用
//        //synchronized (userId.toString().intern()) {
//            //事务提交需要代理对象,这样方法才会被事务管理
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//            //return createVoucherOrder(voucherId);
//        //}
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        //这里加锁，到最后先释放锁，然后才提交事务。并发时还是不行
        //synchronized (userId.toString().intern()) {

            LambdaQueryWrapper<VoucherOrder> lambdaQueryWrapper = new LambdaQueryWrapper<>();
            lambdaQueryWrapper.eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId());
            int count = this.count(lambdaQueryWrapper);
            if (count > 0) {
                log.error("您已经购买过一次,不能重复购买");
            }
            //5.扣减库存，用乐观锁优化，防止超卖
            boolean update = seckillVoucherService.update().setSql("stock=stock-1")
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
            if (!update) {
                log.error("库存不足");
            }

            this.save(voucherOrder);
    }
}
