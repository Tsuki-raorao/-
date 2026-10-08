package com.argus.controlcenter.service;

import com.argus.controlcenter.config.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskControlPolicyTest {
    @Test void disabledIsSafeByDefaultEvenWithLongExistingReadTimeouts() {
        SecurityProperties security=new SecurityProperties();
        AgentGatewayProperties gateway=new AgentGatewayProperties();gateway.setReadTimeout(Duration.ofMinutes(1));
        TaskControlPolicy policy=new TaskControlPolicy(security,gateway,new TaskControlProperties());
        assertThat(policy.enabled()).isFalse();
        assertThat(policy.targetAllowed("any","MOCK")).isFalse();
        assertThat(policy.safeLease()).isGreaterThan(Duration.ofMinutes(3));
    }
    @Test void enabledRequiresEveryIndependentSecurityGateAndBoundedLease() {
        List<Consumer<Fixture>> invalid=List.of(
                fixture->fixture.security.setApiAuthRequired(false),
                fixture->fixture.security.setApiControlToken("view"),
                fixture->fixture.security.setApiAccessToken(""),
                fixture->fixture.gateway.setEnabled(false),
                fixture->fixture.gateway.setAllowControl(false),
                fixture->fixture.gateway.setRequireAllowlist(false),
                fixture->fixture.gateway.setAllowedHosts(Set.of()),
                fixture->fixture.tasks.setAgentControlToken("read"),
                fixture->fixture.tasks.setAgentControlToken(""),
                fixture->fixture.tasks.setLease(Duration.ofSeconds(1)));
        for(Consumer<Fixture> change:invalid) {
            Fixture fixture=new Fixture();change.accept(fixture);
            assertThatThrownBy(fixture::policy).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void configurationChangesAreRecheckedAndReadonlyWins() {
        Fixture fixture=new Fixture();TaskControlPolicy policy=fixture.policy();
        assertThat(policy.targetAllowed("allowed","MOCK")).isTrue();
        assertThat(policy.targetAllowed("other","MOCK")).isFalse();
        assertThat(policy.targetAllowed("allowed","DOCKER")).isFalse();
        fixture.gateway.setAllowControl(false);assertThat(policy.enabled()).isFalse();
        fixture.gateway.setAllowControl(true);fixture.security.setReadOnly(true);
        assertThat(policy.enabled()).isFalse();
        assertThatThrownBy(()->policy.requireOperator(true)).hasMessage("READ_ONLY");
    }
    static class Fixture {
        final SecurityProperties security=new SecurityProperties();
        final AgentGatewayProperties gateway=new AgentGatewayProperties();
        final TaskControlProperties tasks=new TaskControlProperties();
        Fixture(){
            security.setApiAuthRequired(true);security.setApiAccessToken("view");security.setApiControlToken("operator");
            gateway.setEnabled(true);gateway.setAllowControl(true);gateway.setAllowedHosts(Set.of("127.0.0.1"));gateway.setAuthToken("read");
            tasks.setControlEnabled(true);tasks.setAgentControlToken("control");tasks.setAllowedInstanceIds(Set.of("allowed"));
        }
        TaskControlPolicy policy(){return new TaskControlPolicy(security,gateway,tasks);}
    }
}
