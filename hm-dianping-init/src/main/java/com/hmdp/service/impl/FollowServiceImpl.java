package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.resource.StringResource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        //1.判断是否关注
        if(isFollow){
            //2.如果没关注，关注，则将数据写入数据库
            Follow follow = new Follow();
            follow.setFollowUserId(id);
            follow.setUserId(userId);
            boolean isSuccess = this.save(follow);
            if(isSuccess){
                stringRedisTemplate.opsForSet().add("follows:"+userId,id.toString());
            }
        }else{
            //3.如果关注，取关，将数据库信息删除
            LambdaQueryWrapper<Follow> lambdaQueryWrapper=new LambdaQueryWrapper<>();
            lambdaQueryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, id);
            boolean isSuccess = this.remove(lambdaQueryWrapper);
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove("follows:"+userId,id.toString());
            }
        }
        //4.返回
        return Result.ok();
    }

    @Override
    public Result isfollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<Follow> lambdaQueryWrapper=new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, id);
        int count = this.count(lambdaQueryWrapper);
        return Result.ok(count>0);
    }

    @Override
    public Result commonFollow(Long id) {
        //1.获取当前用户id；
        Long userId = UserHolder.getUser().getId();
        String key="follows:"+userId;
        String key2="follows:"+id;
        //2.求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect==null || intersect.isEmpty()){
            return Result.ok();
        }
        //3.获取关注列表，解析id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
