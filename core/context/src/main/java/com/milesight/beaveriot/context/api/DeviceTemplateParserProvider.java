package com.milesight.beaveriot.context.api;

import com.milesight.beaveriot.context.integration.model.BlueprintCreationStrategy;
import com.milesight.beaveriot.context.integration.model.Device;
import com.milesight.beaveriot.context.integration.model.DeviceTemplate;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.context.model.DeviceTemplateModel;
import com.milesight.beaveriot.context.model.response.DeviceTemplateInputResult;
import com.milesight.beaveriot.context.model.response.DeviceTemplateOutputResult;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * author: Luxb
 * create: 2025/5/15 10:06
 **/
public interface DeviceTemplateParserProvider {
    boolean validate(String deviceTemplateContent);
    String defaultContent();
    DeviceTemplateModel parse(String deviceTemplateContent);
    DeviceTemplateInputResult input(String integration, Long deviceTemplateId, Object data);
    DeviceTemplateInputResult input(String integration, Long deviceTemplateId, String deviceIdentifier, String deviceName, Object data);
    DeviceTemplateInputResult input(String integration, Long deviceTemplateId, Object data, Map<String, Object> codecArgContext);
    DeviceTemplateInputResult input(String integration, Long deviceTemplateId, String deviceIdentifier, String deviceName, Object data, Map<String, Object> codecArgContext);
    DeviceTemplateInputResult input(String deviceKey, Object data, Map<String, Object> codecArgContext);
    DeviceTemplateOutputResult output(String deviceKey, ExchangePayload payload);
    DeviceTemplateOutputResult output(String deviceKey, ExchangePayload payload, Map<String, Object> codecArgContext);
    Device createDevice(String integration, Long deviceTemplateId, String deviceId, String deviceName);

    /**
     * Create and persist a device from a stored device template, giving the caller a chance
     * to enrich it first. Mirrors the vendor/model variant but resolves the template directly,
     * so integrations can onboard custom device models that are not published in a blueprint.
     *
     * @param beforeSaveDevice receives the built device and the template metadata; returning
     *                         {@code false} aborts creation
     */
    Device createDevice(String integration,
                        Long deviceTemplateId,
                        String deviceIdentifier,
                        String deviceName,
                        BiFunction<Device, Map<String, Object>, Boolean> beforeSaveDevice);
    Device createDevice(String integration,
                        String vendor,
                        String model,
                        String deviceIdentifier,
                        String deviceName,
                        BiFunction<Device, Map<String, Object>, Boolean> beforeSaveDevice,
                        BlueprintCreationStrategy strategy);
    Device createDevice(String integration,
                        String vendor,
                        String model,
                        String deviceIdentifier,
                        String deviceName,
                        BiFunction<Device, Map<String, Object>, Boolean> beforeSaveDevice);
    DeviceTemplate getLatestDeviceTemplate(String vendor, String model);
}
