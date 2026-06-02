package com.certbatch.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 授权许可证实体
 */
@Data
@TableName("license")
public class License {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权码 */
    private String licenseKey;

    /** 绑定的机器指纹 */
    private String machineId;

    /** 状态: 0=未激活 1=已激活 2=已禁用 */
    private Integer status;

    /** 客户名称 */
    private String customer;

    /** 到期时间 */
    private LocalDateTime expireAt;

    /** 激活时间 */
    private LocalDateTime activatedAt;

    /** 激活后颁发的 token */
    private String token;

    /** RSA 签名（激活时由服务器生成，不存数据库，仅返回给客户端） */
    @TableField(exist = false)
    private String licenseSignature;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
