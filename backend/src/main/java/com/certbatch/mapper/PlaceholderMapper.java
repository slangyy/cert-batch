package com.certbatch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.certbatch.entity.Placeholder;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PlaceholderMapper extends BaseMapper<Placeholder> {

    default List<Placeholder> selectByTemplateId(Long templateId) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Placeholder>()
                .eq(Placeholder::getTemplateId, templateId));
    }

    default int deleteByTemplateId(Long templateId) {
        return delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Placeholder>()
                .eq(Placeholder::getTemplateId, templateId));
    }
}
