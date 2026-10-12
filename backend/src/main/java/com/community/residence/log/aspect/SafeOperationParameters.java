package com.community.residence.log.aspect;

import org.springframework.core.DefaultParameterNameDiscoverer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 操作日志参数白名单：不读取HTTP请求体、未知对象、自由文本或任何凭据。 */
final class SafeOperationParameters {
    private static final Set<String> ID_FIELDS = Set.of("id", "communityId", "buildingId", "unitId", "houseId",
            "residentId", "tenantId", "leaseId", "assigneeId", "staffId", "categoryId", "resourceId", "housingId",
            "appointmentId", "conversationId", "templateId", "userId", "relationId");
    private static final Set<String> LIST_FIELDS = Set.of("ids", "communityIds", "buildingIds", "unitIds", "houseIds", "staffIds", "categoryIds");
    private static final Set<String> DATE_FIELDS = Set.of("moveInDate", "moveOutDate", "startDate", "endDate", "appointmentDate");
    private static final Set<String> ENUM_FIELDS = Set.of("status", "relationType", "role", "channel", "agreementStatus");
    private static final Set<String> ENUM_VALUES = Set.of("ACTIVE", "INACTIVE", "PENDING", "APPROVED", "REJECTED",
            "CANCELLED", "COMPLETED", "CONFIRMED", "VIOLATED", "MOVED_OUT", "ARCHIVED", "VACANT", "OCCUPIED",
            "MAINTENANCE", "OWNER", "TENANT", "COHABITANT", "FAMILY", "DORMITORY", "SUPER_ADMIN", "ADMIN", "STAFF",
            "RESIDENT", "ALIPAY", "WECHAT", "NONE", "PARTIAL", "SIGNED", "ENABLED", "DISABLED", "FROZEN", "RENT", "SALE");
    private static final int MAX_FIELDS = 32;
    private static final int MAX_LIST = 20;
    private static final int MAX_VALUES = 64;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private int valueCount;
    private boolean truncated;

    private SafeOperationParameters() { }

    /* 只反射本项目DTO的声明字段，不调用getter/toString、不递归对象，取不到参数名时不推测。 */
    static Map<String, Object> capture(Method method, Object[] args) {
        var collector = new SafeOperationParameters();
        String[] names = new DefaultParameterNameDiscoverer().getParameterNames(method);
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            Class<?> type = arg.getClass();
            if (type.getName().startsWith("com.community.residence.") && type.getPackageName().endsWith(".dto")) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !collector.allowed(field.getName())) continue;
                    try {
                        if (field.trySetAccessible()) collector.add(field.getName(), field.get(arg));
                    } catch (IllegalAccessException | RuntimeException ignored) {
                        /* 不可读取的参数不影响审计主记录。 */
                    }
                }
            } else if (names != null && i < names.length) {
                collector.add(names[i], arg);
            }
        }
        if (collector.truncated) collector.values.put("_truncated", true);
        return collector.values;
    }

    private boolean allowed(String name) {
        return ID_FIELDS.contains(name) || LIST_FIELDS.contains(name) || DATE_FIELDS.contains(name) || ENUM_FIELDS.contains(name);
    }

    private Long safeId(Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            long number = ((Number) value).longValue();
            return number >= 0 ? number : null;
        }
        return null;
    }

    private void add(String name, Object value) {
        if (value == null || !allowed(name) || values.containsKey(name)) return;
        if (values.size() >= MAX_FIELDS || valueCount >= MAX_VALUES) { truncated = true; return; }
        Object safe = null;
        if (ID_FIELDS.contains(name)) safe = safeId(value);
        if (DATE_FIELDS.contains(name) && (value instanceof LocalDate || value instanceof LocalDateTime)) safe = value.toString();
        if (ENUM_FIELDS.contains(name)) {
            String text = value instanceof Enum<?> e ? e.name() : value instanceof String s ? s : null;
            if (text != null && ENUM_VALUES.contains(text)) safe = text;
        }
        if (LIST_FIELDS.contains(name) && value instanceof List<?> list) {
            List<Long> ids = new ArrayList<>();
            int limit = Math.min(MAX_LIST, MAX_VALUES - valueCount);
            int inspected = 0;
            for (Object item : list) {
                if (inspected++ >= limit) { truncated = true; break; }
                Long id = safeId(item);
                if (id != null) ids.add(id);
            }
            values.put(name, ids);
            valueCount += Math.max(1, ids.size());
            return;
        }
        if (safe != null) { values.put(name, safe); valueCount++; }
    }
}
