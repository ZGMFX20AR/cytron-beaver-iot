package com.milesight.beaveriot.context.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.milesight.beaveriot.base.utils.StringUtils;
import com.milesight.beaveriot.context.integration.model.config.EntityConfig;
import lombok.Data;
import lombok.Getter;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * author: Luxb
 * create: 2025/5/15 13:29
 **/
@Data
public class DeviceTemplateModel {
    private Map<String, Object> metadata;
    private Definition definition;
    private List<EntityConfig> initialEntities;
    private Codec codec;
    private Blueprint blueprint;

    @Getter
    public enum JsonType {
        OBJECT("object"),
        STRING("string"),
        LONG("long"),
        DOUBLE("double"),
        BOOLEAN("boolean");

        private final String typeName;

        JsonType(String typeName) {
            this.typeName = typeName;
        }

        @JsonCreator
        public static JsonType fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Type value cannot be null");
            }
            return Arrays.stream(values())
                    .filter(t -> t.name().equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Invalid type: " + value));
        }
    }

    @Data
    public static class Definition {
        private Input input;
        private Output output;

        @Data
        public static class Input {
            private JsonType type;
            private List<InputJsonObject> properties;
        }

        @Data
        public static class Output {
            private JsonType type;
            private List<OutputJsonObject> properties;
        }

        @Data
        public static class InputJsonObject {
            private String key;
            private JsonType type;
            private String entityMapping;
            private boolean required;
            @JsonProperty("is_device_id")
            private boolean isDeviceId;
            @JsonProperty("is_device_name")
            private boolean isDeviceName;
            private List<InputJsonObject> properties;
        }

        @Data
        public static class OutputJsonObject {
            private String key;
            private JsonType type;
            private String entityMapping;
            private Object value;
            private List<OutputJsonObject> properties;
        }
    }

    @Data
    public static class Codec {
        private String id;
        private String ref;
        /**
         * Inline codec source, used by custom (non-blueprint) device templates.
         * When set, the codec is executed directly instead of being resolved from
         * a blueprint library, so a device model that is not published in the
         * blueprint repository can still decode binary payloads.
         */
        private String code;
        /**
         * Entry function name within {@link #code}.
         */
        private String entry;
        /**
         * Optional entry function used to encode downlinks. Left unset for uplink-only
         * devices, in which case no downlink encoding is available.
         */
        private String encodeEntry;
        /**
         * Ordered arguments passed to {@link #entry}. Exactly one must be marked as the
         * payload, which receives the raw data; the others are resolved by id from the
         * caller's argument context (the LoRaWAN uplink supplies {@code fPort}).
         * <p>
         * When omitted, {@link #getEffectiveArguments()} supplies the common
         * {@code (fPort, payload)} decoder signature.
         */
        private List<CodecArgument> arguments;

        /**
         * Whether this codec carries its own source rather than referencing a blueprint codec.
         */
        @JsonIgnore
        public boolean isInline() {
            return !StringUtils.isEmpty(code) && !StringUtils.isEmpty(entry);
        }

        /**
         * Declared arguments, or the default {@code (fPort, payload)} signature used by
         * most LoRaWAN decoders when none are declared.
         */
        @JsonIgnore
        public List<CodecArgument> getEffectiveArguments() {
            if (!CollectionUtils.isEmpty(arguments)) {
                return arguments;
            }
            return List.of(CodecArgument.of("fPort", false), CodecArgument.of("payload", true));
        }
    }

    @Data
    public static class CodecArgument {
        /**
         * Argument name; for non-payload arguments this is the key looked up in the
         * caller's argument context.
         */
        private String id;
        /**
         * Whether this argument receives the raw payload.
         */
        private boolean payload;

        public static CodecArgument of(String id, boolean payload) {
            CodecArgument argument = new CodecArgument();
            argument.id = id;
            argument.payload = payload;
            return argument;
        }
    }

    @Data
    public static class Blueprint {
        private String dir;
        private Map<String, Value> values;

        @Data
        public static class Value {
            public static final String TYPE_ENTITY_ID = "entity_id";
            public static final String TYPE_ENTITY_KEY = "entity_key";
            public static final String TYPE_DEVICE_ID = "device_id";
            public static final String TYPE_DEVICE_KEY = "device_key";
            private String type;
            private String identifier;
            private Object value;

            public boolean validate() {
                if (StringUtils.isEmpty(type)) {
                    return false;
                }

                if (TYPE_ENTITY_ID.equals(type) || TYPE_ENTITY_KEY.equals(type)) {
                    return !StringUtils.isEmpty(identifier);
                }

                if (TYPE_DEVICE_ID.equals(type) || TYPE_DEVICE_KEY.equals(type)) {
                    return true;
                }

                return value != null;
            }
        }

        public boolean validate() {
            if (StringUtils.isEmpty(dir)) {
                return false;
            }

            if (CollectionUtils.isEmpty(values)) {
                return false;
            }

            for (Value value : values.values()) {
                if (!value.validate()) {
                    return false;
                }
            }
            return true;
        }
    }
}
