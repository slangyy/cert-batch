package com.certbatch.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 模板占位符实体
 */
@Data
@TableName("placeholder")
public class Placeholder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属模板ID */
    private Long templateId;

    /** 占位符名称（与Excel列名对应） */
    private String name;

    /** X坐标 */
    private Double posX;

    /** Y坐标 */
    private Double posY;

    /** 字体名称 */
    private String fontName;

    /** 字体大小 */
    private Integer fontSize;

    /** 字体颜色（十六进制，如 #000000） */
    private String fontColor;

    /** 对齐方式: LEFT / CENTER / RIGHT */
    private String alignment;
}
