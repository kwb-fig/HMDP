package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    //查询发布笔记
    Result queryBlogById(Long id);

    //修改点赞数量
    Result updateLikeById(Long id);

    //展示热点笔记排行
    Result queryHotBlog(Integer current);

    //查询blog点赞列表
    Result queryBlogLikes(Long id);
}
