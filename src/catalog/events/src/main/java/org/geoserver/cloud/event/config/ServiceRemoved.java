/*
 * (c) 2020 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.event.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

import org.geoserver.cloud.event.info.ConfigInfoType;
import org.geoserver.config.ServiceInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonTypeName("ServiceInfoRemoved")
@EqualsAndHashCode(callSuper = true)
public class ServiceRemoved extends ConfigInfoRemoved<ServiceRemoved, ServiceInfo> {

    private @Getter String workspaceId;

    protected ServiceRemoved() {
        // default constructor, needed for deserialization
    }

    protected ServiceRemoved(
            @NonNull Long updateSequence, @NonNull String objectId, String workspaceId) {

        super(updateSequence, objectId, ConfigInfoType.ServiceInfo);
        this.workspaceId = workspaceId;
    }

    public static ServiceRemoved createLocal(
            @NonNull Long updateSequence, @NonNull ServiceInfo info) {

        final @NonNull String serviceId = info.getId();
        final String workspaceId = resolveId(info.getWorkspace());
        return new ServiceRemoved(updateSequence, serviceId, workspaceId);
    }
}
