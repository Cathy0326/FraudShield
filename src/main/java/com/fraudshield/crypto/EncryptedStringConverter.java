package com.fraudshield.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * JPA属性转换器 — 标注了@Convert的字段落库前加密、读取时解密，业务代码零改动
 * JPA attribute converter: fields annotated with @Convert are encrypted on write and
 * decrypted on read - services, controllers, and queries stay completely unchanged.
 *
 * <p>查询参数同样经过converter，配合FieldEncryptor的确定性加密，
 * findByUserId等等值查询照常工作。
 * Query parameters pass through the converter too; combined with deterministic
 * encryption, equality queries like findByUserId keep working.
 *
 * <p>Spring Boot注册了SpringBeanContainer，Hibernate会从Spring容器取converter实例,
 * 所以这里可以构造器注入。
 * Spring Boot wires Hibernate's SpringBeanContainer, so this converter is a Spring bean
 * and constructor injection works.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldEncryptor encryptor;

    public EncryptedStringConverter(FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptor.decrypt(dbData);
    }
}
