/*
 * (c) 2020 Open Source Geospatial Foundation - all rights reserved This code is licensed under the
 * GPL 2.0 license, available at the root application directory.
 */
package org.geoserver.cloud.autoconfigure.ogcapi;

import org.geoserver.cloud.config.factory.FilteringXmlBeanDefinitionReader;
import org.geoserver.cloud.ogcapi.controller.OgcApiController;
import org.geoserver.ogcapi.APIDispatcher;
import org.geoserver.ogcapi.APIRequestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

@Configuration
@ImportResource( //
        reader = FilteringXmlBeanDefinitionReader.class, //
        locations = { //
            "jar:gs-ogcapi-.*!/applicationContext.xml",
            "jar:gs-wms-.*!/applicationContext.xml#name="
                    + OgcApiApplicationAutoConfiguration.WMS_BEANS_BLACKLIST, //
            "jar:gs-wfs-.*!/applicationContext.xml#name="
                    + OgcApiApplicationAutoConfiguration.WFS_BEANS_WHITELIST
        })
public class OgcApiApplicationAutoConfiguration {

    static final String WFS_BEANS_WHITELIST =
            """
            ^(\
            gml.*OutputFormat\
            |bboxKvpParser\
            |featureIdKvpParser\
            |filter.*_KvpParser\
            |cqlKvpParser\
            |maxFeatureKvpParser\
            |sortByKvpParser\
            |xmlConfiguration.*\
            |gml[1-9]*SchemaBuilder\
            |wfsXsd.*\
            |wfsSqlViewKvpParser\
            ).*$\
            """;

    /** wms beans black-list */
    static final String WMS_BEANS_BLACKLIST =
            """
            ^(?!\
            legendSample\
            ).*$\
            """;

    public @Bean OgcApiController OgcApiController() {
        return new OgcApiController();
    }

    private @Autowired APIDispatcher apiDispatcher;

    @Bean
    SetRequestPathInfoFilter setRequestPathInfoFilter() {
        return new SetRequestPathInfoFilter();
    }

    @Bean
    Filter attachApiDispatcherFilter() {
        return new Filter() {
            @Override
            public void doFilter(
                    ServletRequest servletRequest,
                    ServletResponse servletResponse,
                    FilterChain filterChain)
                    throws IOException, ServletException {
                APIRequestInfo requestInfo =
                        new APIRequestInfo(
                                (HttpServletRequest) servletRequest,
                                (HttpServletResponse) servletResponse,
                                apiDispatcher);
                RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
                if (requestAttributes == null) {
                    throw new IllegalStateException("Request attributes are not set");
                }
                requestAttributes.setAttribute(
                        APIRequestInfo.KEY, requestInfo, RequestAttributes.SCOPE_REQUEST);
                filterChain.doFilter(servletRequest, servletResponse);
            }
        };
    }

    static class SetRequestPathInfoFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            request = adaptRequest((HttpServletRequest) request);
            chain.doFilter(request, response);
        }

        /**
         *
         *
         * <ul>
         *   <li>{@code contextPath} is usually empty, but it'll match the gateways
         *       ${geoserver.base-path} if such is set, thanks to the
         *       server.forward-headers-strategy=framework in the application's bootstrap.yml
         *   <li>{@code pathInfo} is computed beforehand to avoid decorating the HttpServletRequest
         *       if the request is not for gwc (e.g. an actuator endpoint)
         *   <li>{@code suffix} is used to strip it out of requestURI and fake the pathInfo gwc
         *       expects as it assumes the request is being handled by a Dispatcher mapped to /**
         *   <li>yes, this is odd, the alternative is re-writing GWC without weird assumptions
         * </ul>
         */
        protected ServletRequest adaptRequest(HttpServletRequest request) {
            // full request URI (e.g. '/geoserver/cloud/{workspace}/gwc/service/tms/1.0.0', where
            // '/geoserver/cloud' is the context path as given by the gateway's base uri, and
            // '/{workspace}/gwc' the suffix after which comes the pathInfo '/service/tms/1.0.0')
            final String requestURI = request.getRequestURI();

            final int ogcIdx = requestURI.indexOf("/ogc");
            if (ogcIdx > -1) {
                final String pathToOgc = requestURI.substring(0, ogcIdx + "/ogc".length());
                final String pathInfo = requestURI.substring(pathToOgc.length());

                return new HttpServletRequestWrapper(request) {
                    public @Override String getPathInfo() {
                        return pathInfo;
                    }
                };
            }
            return request;
        }
    }
}
