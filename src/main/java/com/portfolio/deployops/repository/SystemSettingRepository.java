package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.SystemSetting;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SystemSettingRepository {

    @Select("""
        SELECT setting_key, setting_value, category, created_at, updated_at
        FROM system_setting
        WHERE category = #{category}
        ORDER BY setting_key
        """)
    @Results(id = "systemSettingResult", value = {
        @Result(property = "settingKey", column = "setting_key", id = true),
        @Result(property = "settingValue", column = "setting_value"),
        @Result(property = "category", column = "category"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<SystemSetting> findByCategory(String category);

    @Select("""
        SELECT setting_key, setting_value, category, created_at, updated_at
        FROM system_setting
        WHERE setting_key = #{key}
        """)
    @Results(id = "systemSettingByKeyResult", value = {
        @Result(property = "settingKey", column = "setting_key", id = true),
        @Result(property = "settingValue", column = "setting_value"),
        @Result(property = "category", column = "category"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<SystemSetting> findById(String key);

    @Select("""
        <script>
        SELECT setting_key, setting_value, category, created_at, updated_at
        FROM system_setting
        WHERE setting_key IN
        <foreach collection="keys" item="key" open="(" separator="," close=")">
            #{key}
        </foreach>
        ORDER BY setting_key
        </script>
        """)
    @Results(id = "systemSettingByKeysResult", value = {
        @Result(property = "settingKey", column = "setting_key", id = true),
        @Result(property = "settingValue", column = "setting_value"),
        @Result(property = "category", column = "category"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<SystemSetting> findByKeys(@Param("keys") List<String> keys);

    @Insert("""
        INSERT INTO system_setting (
            setting_key, setting_value, category, created_at, updated_at
        ) VALUES (
            #{settingKey}, #{settingValue}, #{category}, NOW(6), NOW(6)
        )
        """)
    int insert(SystemSetting setting);

    @Update("""
        UPDATE system_setting
        SET setting_value = #{settingValue},
            category = #{category},
            updated_at = NOW(6)
        WHERE setting_key = #{settingKey}
        """)
    int update(SystemSetting setting);

    default SystemSetting save(SystemSetting setting) {
        if (setting.getSettingKey() == null) {
            throw new IllegalArgumentException("settingKey is required");
        }
        if (findById(setting.getSettingKey()).isPresent()) {
            update(setting);
        } else {
            insert(setting);
        }
        return setting;
    }
}
