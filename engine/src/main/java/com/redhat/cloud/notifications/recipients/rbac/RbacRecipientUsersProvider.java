package com.redhat.cloud.notifications.recipients.rbac;

import com.redhat.cloud.notifications.recipients.User;
import com.redhat.cloud.notifications.routers.models.Page;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import dev.failsafe.function.CheckedSupplier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ConnectTimeoutException;
import io.quarkus.cache.CacheResult;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class RbacRecipientUsersProvider {

    private static final Logger LOGGER = Logger.getLogger(RbacRecipientUsersProvider.class);

    @Inject
    @RestClient
    RbacServiceToService rbacServiceToService;

    @ConfigProperty(name = "recipient-provider.rbac.elements-per-page", defaultValue = "1000")
    Integer rbacElementsPerPage;

    @ConfigProperty(name = "rbac.retry.max-attempts", defaultValue = "3")
    int maxRetryAttempts;

    @ConfigProperty(name = "rbac.retry.back-off.initial-value", defaultValue = "0.1S")
    Duration initialBackOff;

    @ConfigProperty(name = "rbac.retry.back-off.max-value", defaultValue = "1S")
    Duration maxBackOff;

    @Inject
    MeterRegistry meterRegistry;

    private Counter failuresCounter;
    private RetryPolicy<Object> retryPolicy;

    @PostConstruct
    public void initCounters() {
        failuresCounter = meterRegistry.counter("rbac.failures");
        retryPolicy = RetryPolicy.builder()
                .onRetry(event -> failuresCounter.increment())
                .handle(IOException.class, ConnectTimeoutException.class)
                .withBackoff(initialBackOff, maxBackOff)
                .withMaxAttempts(maxRetryAttempts)
                .onRetriesExceeded(event -> {
                    // All retry attempts failed, let's log a warning about the failure.
                    LOGGER.warnf("RBAC S2S call failed", event.getException().getMessage());
                })
                .build();
    }

    @CacheResult(cacheName = "rbac-recipient-users-provider-get-users")
    public List<User> getUsers(String accountId, boolean adminsOnly) {
        Timer.Sample getUsersTotalTimer = Timer.start(meterRegistry);
        List<User> users = getWithPagination(
            page -> {
                Timer.Sample getUsersPageTimer = Timer.start(meterRegistry);
                Page<RbacUser> rbacUsers = retryOnError(() ->
                        rbacServiceToService.getUsers(accountId, adminsOnly, page * rbacElementsPerPage, rbacElementsPerPage));
                getUsersPageTimer.stop(meterRegistry.timer("rbac.get-users.page", "accountId", accountId));
                return rbacUsers;
            }
        );
        getUsersTotalTimer.stop(meterRegistry.timer("rbac.get-users.total", "accountId", accountId, "users", String.valueOf(users.size())));
        return users;
    }

    @CacheResult(cacheName = "rbac-recipient-users-provider-get-group-users")
    public List<User> getGroupUsers(String accountId, boolean adminOnly, UUID groupId) {
        Timer.Sample getGroupUsersTotalTimer = Timer.start(meterRegistry);
        RbacGroup rbacGroup = retryOnError(() -> rbacServiceToService.getGroup(accountId, groupId));
        List<User> users;
        if (rbacGroup.isPlatformDefault()) {
            users = getUsers(accountId, adminOnly);
        } else {
            users = getWithPagination(page -> {
                Timer.Sample getGroupUsersPageTimer = Timer.start(meterRegistry);
                Page<RbacUser> rbacUsers = retryOnError(() ->
                        rbacServiceToService.getGroupUsers(accountId, groupId, page * rbacElementsPerPage, rbacElementsPerPage));
                getGroupUsersPageTimer.stop(meterRegistry.timer("rbac.get-group-users.page", "accountId", accountId));
                return rbacUsers;
            });
            // getGroupUsers doesn't have an adminOnly param.
            if (adminOnly) {
                users = users.stream().filter(User::isAdmin).collect(Collectors.toList());
            }
        }
        getGroupUsersTotalTimer.stop(meterRegistry.timer("rbac.get-group-users.total", "accountId", accountId, "users", String.valueOf(users.size())));
        return users;
    }

    private <T> T retryOnError(CheckedSupplier<T> rbacCall) {
        return Failsafe.with(retryPolicy).get(rbacCall);
    }

    private List<User> getWithPagination(Function<Integer, Page<RbacUser>> fetcher) {
        List<User> users = new ArrayList<>();
        int page = 0;
        Page<RbacUser> rbacUsers;
        do {
            rbacUsers = fetcher.apply(page++);
            for (RbacUser rbacUser : rbacUsers.getData()) {
                User user = new User();
                user.setUsername(rbacUser.getUsername());
                user.setEmail(rbacUser.getEmail());
                user.setAdmin(rbacUser.getOrgAdmin());
                user.setActive(rbacUser.getActive());
                user.setFirstName(rbacUser.getFirstName());
                user.setLastName(rbacUser.getLastName());
                users.add(user);
            }
        } while (rbacUsers.getData().size() == rbacElementsPerPage);
        return users;
    }
}
