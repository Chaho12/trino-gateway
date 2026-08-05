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
package io.trino.gateway.ha.router;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.airlift.stats.CounterStat;
import io.trino.gateway.ha.config.DatabaseCacheConfiguration;
import io.trino.gateway.ha.config.ProxyBackendConfiguration;
import io.trino.gateway.ha.config.RoutingConfiguration;
import io.trino.gateway.ha.persistence.dao.BackendRoutingGroup;
import io.trino.gateway.ha.persistence.dao.GatewayBackend;
import io.trino.gateway.ha.persistence.dao.GatewayBackendDao;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public class HaGatewayManager
        implements GatewayBackendManager
{
    private static final Logger log = Logger.get(HaGatewayManager.class);
    private static final Object ALL_BACKEND_CACHE_KEY = new Object();

    private final Jdbi jdbi;
    private final GatewayBackendDao dao;
    private final String defaultRoutingGroup;
    private final LoadingCache<Object, BackendSnapshot> backendCache;

    private final CounterStat backendLookupSuccesses = new CounterStat();
    private final CounterStat backendLookupFailures = new CounterStat();

    /**
     * A consistent view of {@code gateway_backend} and the routing groups every backend belongs to.
     */
    private record BackendSnapshot(List<GatewayBackend> backends, SetMultimap<String, String> routingGroupsByBackend)
    {
        private BackendSnapshot
        {
            backends = ImmutableList.copyOf(backends);
            routingGroupsByBackend = ImmutableSetMultimap.copyOf(routingGroupsByBackend);
        }
    }

    @Inject
    public HaGatewayManager(Jdbi jdbi, RoutingConfiguration routingConfiguration, DatabaseCacheConfiguration databaseCacheConfiguration)
    {
        this(jdbi, routingConfiguration, databaseCacheConfiguration, Ticker.systemTicker());
    }

    @VisibleForTesting
    public HaGatewayManager(Jdbi jdbi, RoutingConfiguration routingConfiguration, DatabaseCacheConfiguration databaseCacheConfiguration, Ticker ticker)
    {
        this.jdbi = requireNonNull(jdbi, "jdbi is null");
        dao = jdbi.onDemand(GatewayBackendDao.class);
        defaultRoutingGroup = routingConfiguration.getDefaultRoutingGroup();

        Caffeine<Object, Object> caffeineBuilder = Caffeine.newBuilder()
                .initialCapacity(1)
                .ticker(ticker);
        if (databaseCacheConfiguration.getExpireAfterWrite() != null) {
            caffeineBuilder = caffeineBuilder.expireAfterWrite(databaseCacheConfiguration.getExpireAfterWrite().toJavaTime());
        }
        if (databaseCacheConfiguration.getRefreshAfterWrite() != null) {
            caffeineBuilder = caffeineBuilder.refreshAfterWrite(databaseCacheConfiguration.getRefreshAfterWrite().toJavaTime());
        }
        if (!databaseCacheConfiguration.isEnabled()) {
            // No-op cache: never stores anything
            caffeineBuilder = caffeineBuilder.maximumSize(0);
        }
        backendCache = caffeineBuilder.build(this::fetchBackendSnapshot);

        // Load the data once during initialization. This ensures a fail-fast behavior in case of database misconfiguration.
        try {
            BackendSnapshot _ = backendCache.get(ALL_BACKEND_CACHE_KEY);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to load gateway backend", e);
        }
    }

    private BackendSnapshot fetchBackendSnapshot(Object ignored)
    {
        try {
            // Both tables are read in a single transaction, so that memberships always match the backends they belong to.
            BackendSnapshot snapshot = jdbi.inTransaction(handle -> {
                GatewayBackendDao transactionDao = handle.attach(GatewayBackendDao.class);
                List<GatewayBackend> backends = transactionDao.findAll();
                ImmutableSetMultimap.Builder<String, String> routingGroups = ImmutableSetMultimap.builder();
                for (BackendRoutingGroup membership : transactionDao.findAllRoutingGroups()) {
                    routingGroups.put(membership.backendName(), membership.routingGroup());
                }
                return new BackendSnapshot(backends, routingGroups.build());
            });
            backendLookupSuccesses.update(1);
            return snapshot;
        }
        catch (Exception e) {
            backendLookupFailures.update(1);
            log.warn(e, "Failed to fetch backends");
            throw e;
        }
    }

    private void invalidateBackendCache()
    {
        // Avoid using bulk invalidation like invalidateAll(), in order to invalidate in-flight loads properly.
        // See https://github.com/trinodb/trino/issues/10512#issuecomment-1016398117
        backendCache.invalidate(ALL_BACKEND_CACHE_KEY);
    }

    private BackendSnapshot getBackendSnapshot()
    {
        try {
            return backendCache.get(ALL_BACKEND_CACHE_KEY);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to load backends from database to cache", e);
        }
    }

    @Override
    public List<ProxyBackendConfiguration> getAllBackends()
    {
        BackendSnapshot snapshot = getBackendSnapshot();
        return upcast(snapshot, snapshot.backends());
    }

    @Override
    public List<ProxyBackendConfiguration> getAllActiveBackends()
    {
        BackendSnapshot snapshot = getBackendSnapshot();
        return upcast(snapshot, snapshot.backends().stream()
                .filter(GatewayBackend::active)
                .collect(toImmutableList()));
    }

    @Override
    public List<ProxyBackendConfiguration> getActiveDefaultBackends()
    {
        try {
            return getActiveBackends(defaultRoutingGroup);
        }
        catch (Exception e) {
            log.info("Error fetching backends for default routing group: %s", e.getLocalizedMessage());
        }
        return ImmutableList.of();
    }

    @Override
    public List<ProxyBackendConfiguration> getActiveBackends(String routingGroup)
    {
        BackendSnapshot snapshot = getBackendSnapshot();
        return upcast(snapshot, snapshot.backends().stream()
                .filter(GatewayBackend::active)
                .filter(backend -> snapshot.routingGroupsByBackend().containsEntry(backend.name(), routingGroup))
                .collect(toImmutableList()));
    }

    @Override
    public Optional<ProxyBackendConfiguration> getBackendByName(String name)
    {
        BackendSnapshot snapshot = getBackendSnapshot();
        return upcast(snapshot, snapshot.backends().stream()
                .filter(backend -> backend.name().equals(name))
                .collect(toImmutableList()))
                .stream()
                .findAny();
    }

    @Override
    public void deactivateBackend(String backendName)
    {
        updateClusterActivationStatus(backendName, false, () -> dao.deactivate(backendName));
    }

    @Override
    public void activateBackend(String backendName)
    {
        updateClusterActivationStatus(backendName, true, () -> dao.activate(backendName));
    }

    private void updateClusterActivationStatus(String clusterName, boolean newStatus, Runnable changeActiveStatus)
    {
        GatewayBackend model = dao.findFirstByName(clusterName);
        checkState(model != null, "No cluster found with name: %s, could not (de)activate", clusterName);

        boolean previousStatus = model.active();
        changeActiveStatus.run();
        logActivationStatusChange(clusterName, newStatus, previousStatus);
        invalidateBackendCache();
    }

    private static void logActivationStatusChange(String clusterName, boolean newStatus, boolean previousStatus)
    {
        if (previousStatus != newStatus) {
            log.info("Backend cluster %s activation status set to active=%s (previous status: active=%s).", clusterName, newStatus, previousStatus);
        }
    }

    @Override
    public ProxyBackendConfiguration addBackend(ProxyBackendConfiguration backend)
    {
        validateBackendConfiguration(backend);
        String backendProxyTo = removeTrailingSlash(backend.getProxyTo());
        String backendExternalUrl = removeTrailingSlash(backend.getExternalUrl());
        // The backend and its routing groups live in two tables and must be written atomically.
        jdbi.useTransaction(handle -> {
            GatewayBackendDao transactionDao = handle.attach(GatewayBackendDao.class);
            transactionDao.create(backend.getName(), backendProxyTo, backendExternalUrl, backend.isActive());
            addRoutingGroups(transactionDao, backend);
        });
        invalidateBackendCache();
        return backend;
    }

    @Override
    public ProxyBackendConfiguration updateBackend(ProxyBackendConfiguration backend)
    {
        validateBackendConfiguration(backend);
        String backendProxyTo = removeTrailingSlash(backend.getProxyTo());
        String backendExternalUrl = removeTrailingSlash(backend.getExternalUrl());
        jdbi.useTransaction(handle -> {
            GatewayBackendDao transactionDao = handle.attach(GatewayBackendDao.class);
            GatewayBackend model = transactionDao.findFirstByName(backend.getName());
            if (model == null) {
                transactionDao.create(backend.getName(), backendProxyTo, backendExternalUrl, backend.isActive());
            }
            else {
                transactionDao.update(backend.getName(), backendProxyTo, backendExternalUrl, backend.isActive());
                logActivationStatusChange(backend.getName(), backend.isActive(), model.active());
            }
            transactionDao.deleteRoutingGroups(backend.getName());
            addRoutingGroups(transactionDao, backend);
        });
        invalidateBackendCache();
        return backend;
    }

    private static void addRoutingGroups(GatewayBackendDao dao, ProxyBackendConfiguration backend)
    {
        for (String routingGroup : backend.getRoutingGroups()) {
            dao.addRoutingGroup(backend.getName(), routingGroup);
        }
    }

    private static void validateBackendConfiguration(ProxyBackendConfiguration backend)
    {
        checkArgument(backend.getName() != null, "Backend name cannot be null");
        checkArgument(backend.getProxyTo() != null, "Backend proxyTo URL cannot be null");
        checkArgument(backend.getExternalUrl() != null, "Backend external url cannot be null");
        List<String> routingGroups = backend.getRoutingGroups();
        checkArgument(!routingGroups.isEmpty(), "Backend must belong to at least one routing group");
        checkArgument(routingGroups.stream().noneMatch(routingGroup -> routingGroup == null || routingGroup.isBlank()), "Backend routing group cannot be null or blank");
        checkArgument(Set.copyOf(routingGroups).size() == routingGroups.size(), "Backend routing groups cannot contain duplicates: %s", routingGroups);
    }

    public void deleteBackend(String name)
    {
        jdbi.useTransaction(handle -> {
            GatewayBackendDao transactionDao = handle.attach(GatewayBackendDao.class);
            transactionDao.deleteRoutingGroups(name);
            transactionDao.deleteByName(name);
        });
        invalidateBackendCache();
    }

    private static List<ProxyBackendConfiguration> upcast(BackendSnapshot snapshot, List<GatewayBackend> gatewayBackendList)
    {
        return gatewayBackendList.stream()
                .map(model -> {
                    ProxyBackendConfiguration backendConfig = new ProxyBackendConfiguration();
                    backendConfig.setActive(model.active());
                    backendConfig.setRoutingGroups(ImmutableList.copyOf(snapshot.routingGroupsByBackend().get(model.name())));
                    backendConfig.setProxyTo(model.backendUrl());
                    backendConfig.setExternalUrl(model.externalUrl());
                    backendConfig.setName(model.name());
                    return backendConfig;
                })
                .collect(toImmutableList());
    }

    public static String removeTrailingSlash(String url)
    {
        return url.replaceAll("/$", "");
    }
}
