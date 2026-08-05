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
package io.trino.gateway.ha.persistence.dao;

import org.jdbi.v3.core.mapper.reflect.ColumnName;

import static java.util.Objects.requireNonNull;

/**
 * A row of {@code gateway_backend_routing_group}: a single membership of a backend
 * cluster in a routing group. A cluster may have several.
 */
public record BackendRoutingGroup(
        @ColumnName("backend_name") String backendName,
        @ColumnName("routing_group") String routingGroup)
{
    public BackendRoutingGroup
    {
        requireNonNull(backendName, "backendName is null");
        requireNonNull(routingGroup, "routingGroup is null");
    }
}
