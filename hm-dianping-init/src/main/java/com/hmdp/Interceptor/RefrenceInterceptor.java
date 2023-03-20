package com.hmdp.Interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefrenceInterceptor implements HandlerInterceptor {

    //拦截全部路径，从而刷新redis时间

    private StringRedisTemplate stringRedisTemplate;
    public RefrenceInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate=stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.从请求头取出token
        String token = request.getHeader("authorization");

        if(StrUtil.isBlank(token)){
            return true;
        }
        //2.基于token查出redis中的用户
        //UserDTO user = (UserDTO) request.getSession().getAttribute("user");
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries("login:token:" + token);
        //3.判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }
        //4.将取出来的用户再转成DTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //5.将用户保存到ThreadLocal中
        UserHolder.saveUser(userDTO);

        //6.刷新token有效期
        stringRedisTemplate.expire("login:token:"+token,30, TimeUnit.MINUTES);
        //7.放行
        return true;
    }
}
