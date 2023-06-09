package com.yuban32.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * @author Yuban32
 * @ClassName UserEditDTO
 * @Description
 * @Date 2023年05月16日
 */
@Data
public class UserEditDTO {
    @NotNull(message = "用户ID不能为空")
    private Long id;
    private String username;
    private String avatar;
    private String password;
    private String email;
}
