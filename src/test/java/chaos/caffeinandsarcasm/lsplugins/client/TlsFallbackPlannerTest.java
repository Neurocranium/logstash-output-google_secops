package chaos.caffeinandsarcasm.lsplugins.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TlsFallbackPlannerTest {

    @Test
    public void triesBothFullEndpointsBeforeRestrictedRegional() {
        EndpointSelector selector = new EndpointSelector("eu", () -> 0L);
        TlsFallbackPlanner planner = new TlsFallbackPlanner(selector, true);

        TlsFallbackPlanner.Decision global = planner.afterHostnameMismatch(
                selector.regionalFullRoute());
        assertFalse(global.isTerminal());
        assertTrue(global.route().isGlobal());
        assertTrue(global.route().isFullVerification());
        assertEquals(TlsFallbackPlanner.Activation.GLOBAL_FULL, global.activation());

        TlsFallbackPlanner.Decision restricted = planner.afterHostnameMismatch(global.route());
        assertFalse(restricted.isTerminal());
        assertTrue(restricted.route().isRegional());
        assertTrue(restricted.route().isRestrictedVerification());
        assertEquals(TlsFallbackPlanner.Activation.REGIONAL_RESTRICTED, restricted.activation());
    }

    @Test
    public void neverUsesRestrictedRegionalWithoutOptIn() {
        EndpointSelector selector = new EndpointSelector("eu", () -> 0L);
        TlsFallbackPlanner planner = new TlsFallbackPlanner(selector, false);

        TlsFallbackPlanner.Decision global = planner.afterHostnameMismatch(
                selector.regionalFullRoute());
        assertTrue(planner.afterHostnameMismatch(global.route()).isTerminal());
    }

    @Test
    public void globalFailureChecksRegionalFullBeforeRestrictedRegional() {
        EndpointSelector selector = new EndpointSelector("eu", () -> 0L);
        TlsFallbackPlanner planner = new TlsFallbackPlanner(selector, true);

        TlsFallbackPlanner.Decision regional = planner.afterHostnameMismatch(
                selector.globalFullRoute());
        assertTrue(regional.route().isRegional());
        assertTrue(regional.route().isFullVerification());
        assertEquals(TlsFallbackPlanner.Activation.REGIONAL_FULL, regional.activation());

        assertTrue(planner.afterHostnameMismatch(regional.route())
                .route().isRestrictedVerification());
    }

    @Test
    public void regional404DoesNotEnableRestrictedCertificateFallback() {
        EndpointSelector selector = new EndpointSelector("eu", () -> 0L);
        TlsFallbackPlanner planner = new TlsFallbackPlanner(selector, true);
        planner.recordRegionalHttp404();

        assertTrue(planner.afterHostnameMismatch(selector.globalFullRoute()).isTerminal());
    }

    @Test
    public void restrictedRegional404DoesNotLoopBackIntoRestrictedFallback() {
        EndpointSelector selector = new EndpointSelector("eu", () -> 0L);
        TlsFallbackPlanner planner = new TlsFallbackPlanner(selector, true);
        EndpointSelector.Route global = planner.afterHostnameMismatch(
                selector.regionalFullRoute()).route();
        assertTrue(planner.afterHostnameMismatch(global).route().isRestrictedVerification());

        planner.recordRegionalHttp404();

        assertTrue(planner.afterHostnameMismatch(global).isTerminal());
    }

    @Test
    public void restrictedRouteMismatchHasNoFurtherFallback() {
        EndpointSelector selector = new EndpointSelector("eu", () -> 0L);
        TlsFallbackPlanner planner = new TlsFallbackPlanner(selector, true);

        assertTrue(planner.afterHostnameMismatch(selector.regionalRestrictedRoute()).isTerminal());
    }
}
