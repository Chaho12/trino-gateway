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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;
import io.airlift.json.JsonMapperProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TestProxyBackendConfiguration
{
    private final JsonMapper jsonMapper = new JsonMapperProvider().get();

    @Test
    void testDefaultRoutingGroups()
    {
        assertThat(new ProxyBackendConfiguration().getRoutingGroups()).containsExactly("adhoc");
    }

    @Test
    void testMultipleRoutingGroups()
    {
        ProxyBackendConfiguration backend = new ProxyBackendConfiguration();
        backend.setRoutingGroups(ImmutableList.of("etl", "adhoc"));
        assertThat(backend.getRoutingGroups()).containsExactly("etl", "adhoc");
        // The deprecated property reports the first group
        assertThat(backend.getRoutingGroup()).isEqualTo("etl");
    }

    @Test
    void testDeprecatedRoutingGroupSetsSingleGroup()
    {
        ProxyBackendConfiguration backend = new ProxyBackendConfiguration();
        backend.setRoutingGroup("etl");
        assertThat(backend.getRoutingGroups()).containsExactly("etl");
    }

    @Test
    void testRoutingGroupsWinOverDeprecatedRoutingGroup()
            throws JsonProcessingException
    {
        // Both orders in the document must yield the same result
        assertThat(deserialize(
                """
                {"name": "c1", "proxyTo": "http://c1", "routingGroup": "etl", "routingGroups": ["etl", "adhoc"]}
                """).getRoutingGroups())
                .containsExactly("etl", "adhoc");
        assertThat(deserialize(
                """
                {"name": "c1", "proxyTo": "http://c1", "routingGroups": ["etl", "adhoc"], "routingGroup": "etl"}
                """).getRoutingGroups())
                .containsExactly("etl", "adhoc");
    }

    @Test
    void testDeserializeDeprecatedRoutingGroupOnly()
            throws JsonProcessingException
    {
        assertThat(deserialize(
                """
                {"name": "c1", "proxyTo": "http://c1", "routingGroup": "etl"}
                """).getRoutingGroups())
                .containsExactly("etl");
    }

    private ProxyBackendConfiguration deserialize(String json)
            throws JsonProcessingException
    {
        return jsonMapper.readValue(json, ProxyBackendConfiguration.class);
    }
}
