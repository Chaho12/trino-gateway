/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.google.common.collect.ImmutableList;

import java.util.List;

public class ProxyBackendConfiguration
{
    private boolean active = true;
    private List<String> routingGroups = ImmutableList.of("adhoc");
    private boolean routingGroupsSet;
    private String externalUrl;
    private String name;
    private String proxyTo;

    @JsonProperty
    public String getName()
    {
        return this.name;
    }

    @JsonProperty
    public String getProxyTo()
    {
        return this.proxyTo;
    }

    @JsonSetter
    public void setName(String name)
    {
        this.name = name;
    }

    @JsonSetter
    public void setProxyTo(String proxyTo)
    {
        this.proxyTo = proxyTo;
    }

    @JsonProperty
    public String getExternalUrl()
    {
        if (externalUrl == null) {
            return getProxyTo();
        }
        return externalUrl;
    }

    @JsonSetter
    public void setExternalUrl(String externalUrl)
    {
        this.externalUrl = externalUrl;
    }

    @JsonProperty
    public boolean isActive()
    {
        return this.active;
    }

    @JsonSetter
    public void setActive(boolean active)
    {
        this.active = active;
    }

    /**
     * The routing groups this cluster serves. A cluster can belong to several groups.
     */
    @JsonProperty
    public List<String> getRoutingGroups()
    {
        return this.routingGroups;
    }

    @JsonSetter
    public void setRoutingGroups(List<String> routingGroups)
    {
        this.routingGroups = routingGroups == null ? ImmutableList.of() : ImmutableList.copyOf(routingGroups);
        this.routingGroupsSet = true;
    }

    /**
     * @deprecated Use {@link #getRoutingGroups()}. Kept for compatibility with clients and
     *         configuration files written before a cluster could belong to multiple routing groups;
     *         it returns the first group.
     */
    @Deprecated
    @JsonProperty
    public String getRoutingGroup()
    {
        return this.routingGroups.isEmpty() ? null : this.routingGroups.getFirst();
    }

    /**
     * @deprecated Use {@link #setRoutingGroups(List)}. Setting a single group is equivalent to
     *         setting a list with one element. Ignored when {@code routingGroups} is also provided,
     *         regardless of the order the two appear in.
     */
    @Deprecated
    @JsonSetter
    public void setRoutingGroup(String routingGroup)
    {
        if (routingGroupsSet) {
            return;
        }
        this.routingGroups = routingGroup == null ? ImmutableList.of() : ImmutableList.of(routingGroup);
    }
}
