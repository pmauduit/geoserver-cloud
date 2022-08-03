/*
 * (c) 2022 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.wms.controller.kml;

import static org.springframework.web.bind.annotation.RequestMethod.GET;

import org.geoserver.kml.KMLReflector;
import org.geoserver.ows.Dispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Controller for {@link KMLReflector} at {@literal /wms/kml?}/{@literal /{workspace}/wms/kml?}
 *
 * <p>Forwards to {@link Dispatcher}, to finally get the request intercepted by the {@code
 * wmsServiceInterceptor-kmlReflector} method advice defined in {@literal
 * gs-kml.jar!/applicationContext.xml}.
 *
 * @since 1.0
 */
public @Controller class KMLReflectorController {

    private @Autowired Dispatcher geoserverDispatcher;

    @RequestMapping(
            method = {GET},
            path = {"/wms/kml", "/{workspace}/wms/kml"})
    public void kmlReflect(HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        geoserverDispatcher.handleRequest(request, response);
    }
}
