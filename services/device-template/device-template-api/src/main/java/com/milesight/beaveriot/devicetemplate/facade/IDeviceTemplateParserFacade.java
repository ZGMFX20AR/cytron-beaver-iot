package com.milesight.beaveriot.devicetemplate.facade;

import com.milesight.beaveriot.context.integration.model.BlueprintCreationStrategy;
import com.milesight.beaveriot.context.integration.model.Device;
import com.milesight.beaveriot.context.integration.model.DeviceTemplate;
import com.milesight.beaveriot.context.integration.model.Entity;
import com.milesight.beaveriot.context.integration.model.ExchangePayload;
import com.milesight.beaveriot.context.model.DeviceTemplateModel;
import com.milesight.beaveriot.context.model.response.DeviceTemplateInputResult;
import com.milesight.beaveriot.context.model.response.DeviceTemplateOutputResult;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public interface IDeviceTemplateParserFacade {
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
    Device createDevice(String integration, Long deviceTemplateId, String deviceIdentifier, String deviceName);

    /**
     * Create and persist a device from a stored device template, letting the caller enrich
     * it first. Used to onboard custom device models that have no blueprint backing.
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

    /**
     * Creates any entities that exist in the device's current template definition but not
     * yet on the device itself - e.g. after a custom device model was edited to add fields
     * after devices were already created from it. Existing entities are left untouched;
     * returns the newly-created entities (empty if the device was already up to date).
     */
    List<Entity> resyncDeviceEntities(String deviceKey);
}
