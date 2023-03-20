package com.hmdp.service.impl;


import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    HttpServletRequest request;
    @Override
    public Result login(LoginFormDTO loginFormDTO) {
        //校验
        String code = loginFormDTO.getCode();
        String phone = loginFormDTO.getPhone();

        //String sessionCode = (String) session.getAttribute(phone);
        //从redis从获取验证码
        String RedisCode = stringRedisTemplate.opsForValue().get("login:code:"+phone);

        //比对成功
        if(RedisCode!=null && code.equals(RedisCode)){
            //查询用户是否是新用户
            LambdaQueryWrapper<User> lambdaQueryWrapper=new LambdaQueryWrapper<>();
            lambdaQueryWrapper.eq(User::getPhone,phone);
            User user = this.getOne(lambdaQueryWrapper);

            if(user==null){
                user=new User();
                user.setNickName("user_"+ RandomUtil.randomString(10));
                user.setPhone(phone);
                this.save(user);
            }
            //保存用户信息到redis中
            //随机生成token，作为登录令牌
            String token = UUID.randomUUID().toString();
            log.info(token);
            //将user对象转成hash存储
            UserDTO userDTO = new UserDTO();
            BeanUtils.copyProperties(user,userDTO);

            //id为long，不能转成string类型
            Map<String, Object> userDTOMap = new HashMap<>();
            Long id = userDTO.getId();
            String uid = String.valueOf(id);
            userDTOMap.put("id",uid);
            userDTOMap.put("NickName",userDTO.getNickName());
            userDTOMap.put("icon",userDTO.getIcon());

//            Map<String, Object> userDTOMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
//                    CopyOptions.create()
//                            .setIgnoreNullValue(true)
//                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            stringRedisTemplate.opsForHash().putAll("login:token:"+token,userDTOMap);

            //设置超时时间
            //从登录到30分钟，就清除redis，所以要动态刷新redis的存储时间
            stringRedisTemplate.expire("login:token:"+token,30,TimeUnit.MINUTES);

            //返回token
            return Result.ok(token);
        }
        return Result.fail("验证码错误");
    }
    @Override
    public Result logout() {
        String token = request.getHeader("authorization");
        stringRedisTemplate.delete("login:token:"+token);
        return Result.ok("退出成功");
    }

    @Override
    public Result sendMsg(String phone) {
        if(phone==null || RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }

        //2.发送验证码
        String code = RandomUtil.randomNumbers(6);

        //3.将验证码存入session
        //session.setAttribute(phone,code);
        //存入redis中
        stringRedisTemplate.opsForValue().set("login:code:"+phone,code,5, TimeUnit.MINUTES);
        log.info("code+{}",code);
        return Result.ok();
    }

    @Override
    public Result sign() {
        //格式张三2022/02
        //1.首先获取用户信息
        Long userId = UserHolder.getUser().getId();

        //2.获取时间信息
        LocalDateTime now = LocalDateTime.now();
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyyMM"));

        //3.拼接key
        String key="sign:"+userId+yyyyMM;

        //4.获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();

        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.首先获取用户信息
        Long userId = UserHolder.getUser().getId();

        //2.获取时间信息
        LocalDateTime now = LocalDateTime.now();
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyyMM"));

        //3.拼接key
        String key="sign:"+userId+yyyyMM;
        //4.获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        List<Long> longs = stringRedisTemplate.opsForValue()
                .bitField(
                        key,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(longs==null || longs.isEmpty()){
            return Result.ok(0);
        }
        Long num = longs.get(0);

        //连续签到次数就是从最后一天签到往前，遇到1加1，遇到0break;
        int count=0;
        while(true){
            if((num & 1)==0) break;
            else count++;
            num=num>>1;
        }
        return Result.ok(count);
    }


}
