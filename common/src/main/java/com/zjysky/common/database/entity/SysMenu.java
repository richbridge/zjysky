package com.zjysky.common.database.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 前端菜单：sys_menu。一行 = 一个分组（parentId=0）或一个菜单项。 */
@Data
@TableName("sys_menu")
public class SysMenu {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父分组 id；0 表示这一行是分组。 */
    private Long parentId;
    /** 稳定标识：分组 key 或菜单项 key。 */
    private String menuKey;
    private String title;
    /** 前端路由地址；分组为 null。 */
    private String path;
    /** 前端组件注册名；找不到时渲染占位页。 */
    private String component;
    private Integer sortNo;
    /** 1 显示 / 0 隐藏。 */
    private Integer visible;
    private Integer closable;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
