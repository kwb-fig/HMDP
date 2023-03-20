package com.hmdp.dto;

import lombok.Data;

@Data
public class UserDTO {
    //隐藏用户敏感数据
    private Long id;
    private String nickName;
    private String icon;
}
