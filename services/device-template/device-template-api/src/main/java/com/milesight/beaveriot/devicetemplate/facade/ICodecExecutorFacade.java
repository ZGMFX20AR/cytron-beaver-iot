package com.milesight.beaveriot.devicetemplate.facade;

import com.milesight.beaveriot.context.model.BlueprintLibrary;
import com.milesight.beaveriot.context.model.DeviceTemplateModel;

/**
 * author: Luxb
 * create: 2025/9/16 9:00
 **/
public interface ICodecExecutorFacade {
    IDeviceCodecExecutorFacade getDeviceCodecExecutor(BlueprintLibrary blueprintLibrary, String vendor, String model);

    /**
     * Build a codec executor from a device template that carries its own codec source.
     * <p>
     * Used by custom device models that are not published in a blueprint library, so
     * binary payloads can still be decoded without any blueprint lookup.
     *
     * @return the executor, or {@code null} when the template declares no inline codec
     */
    IDeviceCodecExecutorFacade getInlineDeviceCodecExecutor(DeviceTemplateModel deviceTemplateModel);
}
