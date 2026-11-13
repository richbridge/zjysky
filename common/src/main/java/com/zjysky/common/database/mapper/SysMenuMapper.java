package com.zjysky.common.database.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zjysky.common.database.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** sys_menu（前端菜单），基础 CRUD 走 BaseMapper。 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /** 全部菜单行（含隐藏项），按分组 / 排序 / 主键排列。 */
    List<SysMenu> listAll();
}
