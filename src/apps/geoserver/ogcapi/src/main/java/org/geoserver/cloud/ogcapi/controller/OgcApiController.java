/*
 * (c) 2020 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.ogcapi.controller;

import org.geoserver.ogcapi.APIDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Controller
public class OgcApiController {

    private @Autowired APIDispatcher apiDispatcher;

    @RequestMapping(path = {"/**"})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws Exception {
        apiDispatcher.handleRequest(request, response);
    }
}
