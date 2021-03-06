/*
 * Copyright 2017 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apiman.plugins.auth3scale.authrep.appid;

import static io.apiman.plugins.auth3scale.Auth3ScaleConstants.REFERRER;
import static io.apiman.plugins.auth3scale.Auth3ScaleConstants.USER_ID;
import static io.apiman.plugins.auth3scale.util.Auth3ScaleUtils.buildLog;
import static io.apiman.plugins.auth3scale.util.Auth3ScaleUtils.buildRepMetrics;

import io.apiman.gateway.engine.beans.ApiRequest;
import io.apiman.gateway.engine.beans.ApiResponse;
import io.apiman.gateway.engine.policy.IPolicyContext;
import io.apiman.gateway.engine.threescale.beans.Auth3ScaleBean;
import io.apiman.gateway.engine.threescale.beans.BackendConfiguration;
import io.apiman.plugins.auth3scale.Auth3ScaleConstants;
import io.apiman.plugins.auth3scale.authrep.RepPrincipal;
import io.apiman.plugins.auth3scale.authrep.strategies.RepStrategy;
import io.apiman.plugins.auth3scale.util.Auth3ScaleUtils;

import java.net.URI;

/**
 * @author Marc Savy {@literal <msavy@redhat.com>}
 */
public class AppIdRep implements RepPrincipal {

    private final BackendConfiguration config;
    private final ApiRequest request;
    private final ApiResponse response;
    private final RepStrategy rep;
    private final URI endpoint;

    public AppIdRep(Auth3ScaleBean auth3ScaleBean,
            ApiRequest request,
            ApiResponse response,
            IPolicyContext context,
            RepStrategy rep) {
                this.endpoint = Auth3ScaleUtils.parseUri(auth3ScaleBean.getBackendEndpoint() + Auth3ScaleConstants.REPORT_PATH);
                this.config = auth3ScaleBean.getThreescaleConfig().getProxyConfig().getBackendConfig();
                this.request = request;
                this.response = response;
                this.rep = rep;
    }

    @Override
    public RepPrincipal rep() {
        String appId = AppIdUtils.getAppId(config, request);
        String appKey = AppIdUtils.getAppKey(config, request);
        // Otherwise build report to be encoded.
        AppIdReportData report = new AppIdReportData()
                .setEndpoint(endpoint)
                .setReferrer(request.getHeaders().get(REFERRER))
                .setServiceToken(config.getBackendAuthenticationValue())
                .setServiceId(Long.toString(config.getProxy().getServiceId()))
                .setAppId(appId)
                .setAppKey(appKey)
                .setUserId(getUserId())
                .setUsage(buildRepMetrics(config, request))
                .setLog(buildLog(response));
        rep.setKeyElems(appId, appKey);
        rep.setReport(report);
        rep.rep();
        return this;
    }

    private String getUserId() {
        return request.getHeaders().get(USER_ID);
    }
}
