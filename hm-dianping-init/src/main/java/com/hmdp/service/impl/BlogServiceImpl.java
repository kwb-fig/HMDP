package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
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
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    UserServiceImpl userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);

        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        //查询是否点赞
        //2.从redis查询点赞信息
        Double score = stringRedisTemplate.opsForZSet()
                .score("blog:liked:" + id, UserHolder.getUser().getId().toString());
        blog.setIsLike(score!=null);
        return Result.ok(blog);
    }

    @Override
    public Result updateLikeById(Long id) {
        //1.获取用户信息
        Long userId = UserHolder.getUser().getId();
        if(userId==null){
            return Result.fail("请先登录");
        }
        //2.从redis查询点赞信息
        Double score = stringRedisTemplate.opsForZSet()
                .score("blog:liked:" + id, userId.toString());

        //3.如果没有信息,点击就加一
        if(score==null){
            boolean isSuccess = update().setSql("liked=liked + 1").eq("id", id).update();
            //3.1把数据保存到redis中
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add("blog:liked:"+id,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4.如果有信息，点击就减一
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if(isSuccess){
                //4.1将redis中数据移除
                stringRedisTemplate.opsForZSet().remove("blog:liked:"+id,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            UserDTO userDTO = UserHolder.getUser();
            if(userDTO!=null) {
                //2.从redis查询点赞信息
                Double score = stringRedisTemplate.opsForZSet()
                        .score("blog:liked:" + blog.getShopId(), UserHolder.getUser().getId().toString());
                blog.setIsLike(score!=null);
            }
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5点赞的用户
        Set<String> range = stringRedisTemplate.opsForZSet().range("blog:liked:" + id, 0, 4);
        if(range==null || range.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析其中id
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        //3.根据id查询用户
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }
}
