package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 基于redis中间件查询商铺类型信息
     * @return
     */
    @Override
    public Result getShopTypes() {
        //查询Redis中的缓存
        String valuesJson = stringRedisTemplate.opsForValue().get(SHOP_LIST_KEY);
        //如果有，直接返回
        if (StrUtil.isNotBlank(valuesJson)) {
            List<ShopType> shopTypes = JSONUtil.toList(valuesJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        //如果没有，则查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //如果不存在，则返回错误信息
        if (shopTypes == null) {
            return Result.fail("无商铺分类");
        }
        //如果存在，先存到redis中，再返回
        stringRedisTemplate.opsForValue().set(SHOP_LIST_KEY, JSONUtil.toJsonStr(shopTypes));
        return Result.ok(shopTypes);
    }
}
